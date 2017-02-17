# Continuous Queries

The CONTINUOUS option creates a long running query filter that process all
updates to the local index. The query specification is compiled into an
efficient query filter that evaluates the document updates, and if the filter
evaluates to true, the query task service is PATCHed with a document results reflecting
the self link (and document if EXPAND is set) that changed.

The continuous query task service acts as a node wide black board, or notification
service allowing clients or services to receive notifications without having to
subscribe to potentially millions of discrete services.

Here are the basic steps required to efficiently use the continuous query tasks.

1. Create continuous query task request
2. Send query task request
3. On completion of the request, subscribe to the created query task service
4. Implement handler that will be called on notifications from query task service with latest results

We can avoid setting up the subscription with query task here, and instead do the polling on this continues query task service for updates. But that would not be efficient. Instead we recommend using subscription model here and get the results whenever they are available from our friend on the other side.

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

After sending the query we need to capture the returned query task service link and subscribe to it for any updates.

```java
Operation post = Operation.createPost(this.clientHost, ServiceUriPaths.CORE_LOCAL_QUERY_TASKS)
       .setBody(queryTask)
       .setReferer(this.clientHost.getUri());

post.setCompletion((o, e) -> {
   if (e != null) {
       System.out.printf("Query failed %s", e.toString());
       return;
   }
   QueryTask queryResponse = o.getBody(QueryTask.class);
   subscribeToContinuousQueryTask(this.clientHost, queryResponse.documentSelfLink);
);

 this.clientHost.sendRequest(post);
```

## Subscribe to the results

Notice above, in the completion handler we are calling `subscribeToContinuousQueryTask` (shown bellow) method with the selfLink of the query task service.

```java
public void subscribeToContinuousQueryTask(ServiceHost host, String serviceLink) {
     Consumer<Operation> target = this::processResults;
     
     Operation subPost = Operation
            .createPost(UriUtils.buildUri(host, serviceLink))
            .setReferer(host.getUri());
            
     host.startSubscriptionService(subPost, target);
}

```

`subscribeToContinuousQueryTask` is just calling `startSubscriptionService` method on the local host that will listen for any notifications from the the target service and call our target method(`processResults`).

## Process the results

Following is the basic implementation of `processResults` that would be called whenever the query task service on the target host has any new data for us to process. We check for presence of the results and then loop over all the result documents to process. 

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
