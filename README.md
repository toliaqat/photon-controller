# Continuous Queries

Query tasks have a special option to create Continuous queries.
Continuous queries are great way to get update notifications on your filters.
Continuous query is started by creating a query task service with CONTINUOUS
option, and setting up a notification on that query task instance. A continuous query
task does two things:
 1. It will self patch, causing the initial notification, once the initial query results are available, providing the documents currently in the index.
 2. On any additional index updates that match the query associated with the
task, it will once again self patch, with the result being the document that
changed or was added. Once again this will cause notifications to be sent.

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
not be efficient. Instead we recommend using subscription model here and get
the results whenever they are available.

In rest of the document we will go over the steps mentioned above.

## Continuous Query Task request

Following is the simple example of QueryTask in which CONTINUOUS query option is selected.
This query task is filtering the results based on Kind field clause.

```java
public QueryTask createContinuousQuery() {
    QueryTask.Query query = QueryTask.Query.Builder.create()
            .addKindFieldClause(EmployeeService.Employee.class)
            .build();

    QueryTask queryTask = QueryTask.Builder.create()
            .addOption(QueryOption.EXPAND_CONTENT)
            .addOption(QueryOption.CONTINUOUS)
            .setQuery(query).build();
    return queryTask;
}
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

## Sending the request and subscribing to notifications

After creating the query task, we POST it to the local-query-tasks factory service
with specific documentSelfLink (QUERY_TASK_LINK) that will create a query task with
that name, helping us to point to this query task service easily in future.

On creation of this continuous query task service we call `subscribeToContinuousQuery`
to create the subscription to get notifications from query task service with updates.

**Note:** We always use a *local* query task (CORE_LOCAL_QUERY_TASKS) for continuous queries!

*See also* [[DeferredResult-Tutorial]]

```java
public void createAndSubscribeToContinuousQuery(Operation op) {
    QueryTask queryTask = createContinuousQuery();
    queryTask.documentSelfLink = QUERY_TASK_LINK;
    Operation post = Operation.createPost(getHost(), ServiceUriPaths.CORE_LOCAL_QUERY_TASKS)
            .setBody(queryTask)
            .setReferer(getHost().getUri());

    getHost().sendWithDeferredResult(post)
            .thenAccept((state) -> subscribeToContinuousQuery())
            .whenCompleteNotify(op);
}
```

Now let us see the code for `subscribeToContinuousQuery` which will create start
subscription service to listen for any notifications from continuous query task service
we created earlier. We are using `startSubscriptionService` API of the ServiceHost
to setup the subscription service. This API is creating a subscription service with a
callback URI, and registring that URI with the continuous query task service.

Please read the section "Details!" for mode details on creating a robust solution.

```java
public void subscribeToContinuousQuery() {
    Operation post = Operation
            .createPost(UriUtils.buildUri(getHost(), QUERY_TASK_LINK))
            .setReferer(getHost().getUri());

    URI subscriptionUri = getHost().startSubscriptionService(post, this::processResults);
    updateSubscriptionLink(subscriptionUri);
}
```


*See also section on Subscriptions in [[Programming-Model]]*

## Process the results

Following is the basic implementation of `processResults` that would be called
whenever the query task service on the target host has any new data for us to
process. We check for presence of the results and then loop over all the result
documents to process. During the processing we can check `state.documentUpdateAction`
to see what was the last action (created, deleted) on that service.

```java
public void processResults(Operation op) {
    QueryTask body = op.getBody(QueryTask.class);

    if (body.results == null || body.results.documentLinks.isEmpty()) {
        return;
    }

    for (Object doc : body.results.documents.values()) {
        EmployeeService.Employee state = Utils.fromJson(doc, EmployeeService.Employee.class);
        getHost().log(Level.INFO, "Employee Name: %s, Action: %s", state.name, state.documentUpdateAction);
    }
}
```

## Details!
So far we have went over the basics of continuous query tasks. But there are
few things, we need to be aware of, when we are writing code for production.

In a multi-node environment, one important thing to note
is that continuous query task service, being a long running service, that hits the
index regularly for updates. Hence this should be used with care and should be
called by a single host. If above code is running on a replicated service
on multiple nodes, then we would be triggering this continuous query task
multiple times on our host, which would be a over kill and can be big
performance hit on your hosts.

That means when we are creating continuous queries, we need to make
sure, that the caller with subscription to notifications is fault-tolarant and
load-balanced. We do that by making a stateful service which is replicated,
and owner-selected. Lets call this service a watch service.

Because it will be a replicated service, it will give us
fault-tolerance in case one of the replica goes down. And beause this is
owner-selected we can make sure that only one node is doing the heavy lifting
at anyone time, and other nodes are not dublicating the work. That watch
service will create the continuous query task and watch for notifications from
it. But not just that, we would want it to do this only on one of the
replicated nodes, not on all nodes. How do we make sure that stateful service
is only creating the query task in one of the replcas? We can do that by
checking for the ownership of its state in handleNodeGroupMaintenance method.

What is `handleNodeGroupMaintenance`? This is overridable method in stateful
services that is invoked by the Xenon runtime when there is change in the
ownership of the state of the service.

We can override this method in our watch service and check if we are owner or
not using `hasOption(ServiceOption.DOCUMENT_OWNER)`, and based on that create
continuous query task if we are owner, and delete running continuous query task
service and subscription if we are not the owner anymore.
Following is the example code that implements `handleNodeGroupMaintenance` 

```
@Override
public void handleNodeGroupMaintenance(Operation op) {
    // Create continuous queries and subscriptions in case of change in node group topology.
    if (hasOption(ServiceOption.DOCUMENT_OWNER)) {
        createAndSubscribeToContinuousQuery(op);
    } else {
        deleteSubscriptionAndContinuousQuery(op);
    }
}
```

We have already seen the basic implementation of
`createAndSubscribeToContinuousQueryTask` earlier. Now lets see what
`deleteSubscriptionAndContinuousQuery` might look like in this watch service.

```
private void deleteSubscriptionAndContinuousQuery(Operation op) {
    Operation unsubscribeOperation = Operation.createPost(UriUtils.buildUri(getHost(), QUERY_TASK_LINK))
            .setReferer(getUri())
            .setCompletion((o, e) -> {
                updateSubscriptionLink(null);
                deleteContinuousQuery();
            });

    getStateAndApply(state -> getHost().stopSubscriptionService(unsubscribeOperation,
            UriUtils.buildUri(state.subscriptionLink)));
}
```
In above method we are first creating a `Operation` to unsubscribe the
subscription service being created by the runtime. `stopSubscriptionService` deletes
both the subscriber entry in the query task service and also deletes the subscription callback
endpoint.
On the completion of these deletions we are deleting the continuous query service by
calling `deleteContinuousQuery`.

Following is the implementation of `deleteContinuousQuery`.
```
private void deleteContinuousQuery() {
    getHost().sendRequest(Operation
            .createDelete(UriUtils.buildUri(getHost(), QUERY_TASK_LINK))
            .setReferer(getUri()));
}
```

Following is the implementation of `getStateAndApply`.
```
private void getStateAndApply(Consumer<? super State> action) {
    Operation get = Operation
            .createGet(this, this.getSelfLink())
            .setReferer(getUri());

    getHost().sendWithDeferredResult(get, State.class)
            .thenAccept(action)
            .whenCompleteNotify(get);
} 
```

## FAQ

#### Can I do continuous query on a stateless service?
No, because queries work on stateful and persisted services.

#### How can I see the list of all created continuous query services?
You can do curl on `http://[host]/core/local-query-tasks` to see list of all local query task services.

#### My continuous query task is terminated after few minutes?
It got expired after 10 minutes. Set it to never expire using `querytask.documentExpirationTimeMicros = Long.MAX_VALUE;`

#### Why I am not getting any results in subscription of my continues query tasks service?
Subscription handler will be called when there are any updates. Make sure your
updates are being reflected on the index.

#### Let’s say I’ve got a query for “all example services”, and one of the example services is deleted: do I learn that it’s no longer in the query result?
You will get a `PATCH`, with the body being the last version of the example
service when it was deleted. The `documentUpdateAction` will be `DELETE`.

#### You said, any update that satisfies the query filter will cause the results to be updated and a self PATCH to be sent on the service. Does that mean I’ll see a diff of new things that match that query?

You will receive notifications in the form of PATCH operations, with the body
being a QueryTask, with the results.documentLinks/documents being filled in
with the specific update to a service that matched the query if your query no
longer match anything, you get no notifications. You can cancel it, have it
expire, etc.

#### When does a continuous query ends?
It never ends, until it gets expired. You will keep getting notification as
long as there are updates that fulfil continuous query task's filter or until it gets expired.

#### How do you calculate a total sum using a continuous query when you don't know when your are done getting update notifications?
Well, a total sum implies you know the full set that you want to compute it
over, which means that you can use a normal query. If you want to keep
counting, and do a running sum, then you need to use a continuous query.
