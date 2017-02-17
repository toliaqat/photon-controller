# Continuous Queries

Continuous Queries are great way to get update notifications on your filters.
Continuous query is started by creating a query task service with CONTINUOUS
option. And then setting up a notification on that service. A continuous query
task does two things:
 1. It will give the historical, existing data as the first notification
 2. It will give you any further updates

In the remainder of the document we expect you have basic understanding of [[QueryTaskService]].

The CONTINUOUS option creates a long running query filter that process all
updates to the local index. The query specification is compiled into an
efficient query filter that evaluates the document updates, and if the filter
evaluates to true, the query task service is PATCHed with a document results reflecting
the self-link (and document if EXPAND is set) that changed.

The continuous query task service acts as a node wide black board, or notification
service allowing clients or services to receive notifications without having to
subscribe to potentially millions of discrete services.

Here are the basic steps required to efficiently use the continuous query tasks.

1. Create continuous query task request
2. Send query task request
3. On completion of the request, subscribe to the created query task service self-link
4. Implement handler that will be called on notifications from query task service with updates

We can avoid setting up the subscription with query task here, and instead do
the polling on this continues query task service for updates. But that would
NOT be efficient. Instead we recommend using subscription model here and get
the results whenever they are available from this friend on the other side.

In rest of the document we will go over the steps mentioned above.

## Continuous Query Task request

Following is the simple example of QueryTask in which CONTINUOUS query option is selected.
This query task is filtering the results based on Kind field clause.

```java
QueryTask.Query query = QueryTask.Query.Builder.create()
        .addKindFieldClause(Employee.class)
        .build();

QueryTask queryTask = QueryTask.Builder.create()
        .addOption(QueryOption.EXPAND_CONTENT)
        .addOption(QueryOption.CONTINUOUS)
        .setQuery(query).build();
```

By default, query tasks services expire after few (10 at the time of writing)
minutes. If your continuous result collection is supposed to complete within
that time limit then you are fine, otherwise you *SHOULD* increase this expiry
limit to require time limit. Following code line is setting the expiry to
be unlimited for query task service we are creating.

```
querytask.documentExpirationTimeMicros = Long.MAX_VALUE;
```

Json payload of above query.

```json
{
    "taskInfo": {
        "isDirect": false
    },
    "querySpec": {
        "query": {
            "occurance": "MUST_OCCUR",
            "booleanClauses": [
                {
                    "occurance": "MUST_OCCUR",
                    "term": {
                        "propertyName": "documentKind",
                        "matchValue": "com:vmware:myproject:EmployeeService:Employee",
                        "matchType": "TERM"
                    }
                }
            ]
        },
        "options": [
            "CONTINUOUS",
            "EXPAND_CONTENT"
        ]
    },
    "indexLink": "/core/document-index",
    "nodeSelectorLink": "/core/node-selectors/default",
    "documentVersion": 0,
    "documentUpdateTimeMicros": 0,
    "documentExpirationTimeMicros": 0
}
```

## Send the request

After sending the query we need to capture the returned query task service link
and subscribe to it for any updates.  *NOTE:* One important thing to note here
is that continuous query task service being a long running service that queries the
index regularly for updates. Hence this should be used with care and should be
called by a single host. If following code is running on a replicated service
on multiple nodes, then we would be triggering this continues query task
multiple times on our targetHost, which would be a over kill and can be big
performance hit on your poor hosts.

```java
Operation post = Operation.createPost(targetHost, ServiceUriPaths.CORE_LOCAL_QUERY_TASKS)
     .setBody(queryTask)
     .setReferer(this.clientHost.getUri());

post.setCompletion((o, e) -> {
     if (e != null) {
          System.out.printf("Query failed %s", e.toString());
          return;
     }
     QueryTask queryResponse = o.getBody(QueryTask.class);
     subscribeToContinuousQueryTask(targetHost, queryResponse.documentSelfLink);
);

this.clientHost.sendRequest(post);
```

## Subscribe to the results

Notice above, in the completion handler we are calling
`subscribeToContinuousQueryTask` (shown below) method with the self-Link of the
query task service.

```java
public void subscribeToContinuousQueryTask(ServiceHost host, String serviceLink) {
     Consumer<Operation> target = this::processResults;

     Operation subPost = Operation
          .createPost(UriUtils.buildUri(host, serviceLink))
          .setReferer(host.getUri());

     host.startSubscriptionService(subPost, target);
}

```

`subscribeToContinuousQueryTask` is just calling `startSubscriptionService`
method on the local host that will listen for any notifications from the the
target service and call our target method(`processResults`).

## Process the results

Following is the basic implementation of `processResults` that would be called
whenever the query task service on the target host has any new data for us to
process. We check for presence of the results and then loop over all the result
documents to process.

```java
public void processResults(Operation op) {
     QueryTask body = op.getBody(QueryTask.class);

     if (body.results == null || body.results.documentLinks.isEmpty()) {
          return;
     }

     for (Object doc : body.results.documents.values()) {
          Employee state = Utils.fromJson(doc, Employee.class);
          System.out.printf("Name %s \n", state.name);
     }
}
```
## FAQ

#### Can I do continuous query on a stateless service?
No, because queries work on stateful and persisted services.

#### How can I see list of all created continuous query services?
You can do curl on `http://host/core/query-tasks` and `http://host/core/local-query-tasks` to see list of all query task services.

#### My continuous query task is not there after few minutes. Why it got disappeared?
It got expired after 10 minutes. Set it to never expire using `querytask.documentExpirationTimeMicros = Long.MAX_VALUE;`

#### Why I am not getting any results in subscription of my continues query tasks service?
Subscription handler will be called when there are any updates. Make sure your
updates are being reflected on the index.

#### Let’s say I’ve got a query for “all example services”, and one of the example services is deleted: do I learn that it’s no longer in the query result?
You will get a PATCH, with the body being the last version of the example
service when it was deleted. The documentUpdateAction will be DELETE.

#### You said, any update that satisfies the query filter will cause the results to be updated and a self PATCH to be sent on the service. Does that mean I’ll see a diff of new things that match that query?

You will receive notifications in the form of PATCH operations, with the body
being a QueryTask, with the results.documentLinks/documents being filled in
with the specific update to a service that matched the query if your query no
longer match anything, you get no notifications. You can cancel it, have it
expire, etc.

#### I want to get notification on every single create, update, delete on all services on my host. Will a single continuous query task do that?
Please don't try this at your home(production). Yes, you can do that, but it
will be a big performance hit on your host for the duration of your query task.
Make sure you set expire to be short.

#### When does a continuous query ends?
It never ends, until it gets expired. You will keep getting notification as
long as there are updates that fulfil continuous query task's filter.

#### How do you calculate a total sum using a continuous query when you don't know when your are done getting update notifications?
Well a total sum implies you know the full set that you want to compute it
over, which means that you can use a normal query. If you want to keep
counting, and do a running sum, then you need to use a continuous query.

#### Ok, I am still confused. What does a continuous query actually do?
A continuous query does two things:
 1. It will give the historical, existing data as the first notification
 2. It will give you any further updates
