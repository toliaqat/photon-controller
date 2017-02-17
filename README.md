# Continuous Queries

The CONTINUOUS option creates a long running query filter that process all
updates to the local index. The query specification is compiled into an
efficient query filter that evaluates the document updates, and the filter
evaluates to true, the query task is PATCHed with a document results reflecting
the self link (and document if EXPAND is set) that changed.

The continuous query task acts as a node wide black board, or notification
service allowing clients or services to receive notifications without having to
subscribe to potentially millions of discrete services.

Following is the example of QueryTask in which CONTINUOUS query option is selected.

<style>
div.hidecode + pre {display: none}
</style>

<div class="hidecode"></div>
>!test 

```{java}
QueryTask queryTask = QueryTask.Builder.create()
       .addOption(QueryOption.EXPAND_CONTENT)
       .addOption(QueryOption.CONTINUOUS)
       .setQuery(query).build();

Operation post = Operation.createPost(this.clientHost, ServiceUriPaths.CORE_LOCAL_QUERY_TASKS)
       .setBody(queryTask)
       .setReferer(this.clientHost.getUri());
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
