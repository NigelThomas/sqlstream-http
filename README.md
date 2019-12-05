# sqlstream-http

Java utilities intended to be used with SQLstream to create UDFs and UDXs.

```
package com.sqlstream.utility.http;
```

## HttpPost UDX

*   Takes a JSON input stream and batches of records to the output (as an array)
*   Sends a maximum of N rows in a batch (configurable)
*   Sends a part batch after a timeout (configurable)

```
 CREATE OR REPLACE FUNCTION "HttpPost"
     ( inputRows            CURSOR         -- input stream
     , optionRows           CURSOR         -- options that can be set at run time
     , postDataColumnName   VARCHAR(128)
     )
    RETURNS STREAM
           ( inputRows.*
           , "responseCode" INTEGER          -- the HTTP result
           , "httpResponse" VARCHAR(64000) -- the HTTP response (message data)
           )
    LANGUAGE JAVA
    PARAMETER STYLE SYSTEM DEFINED JAVA
    NO SQL
    EXTERNAL NAME 'class com.sqlstream.utilities.http.HttpPost.httpPost'
 ;
```

## Options

Options can be provided as a CURSOR (so can be extracted from a source table or file that
can be configured appropriately - for example with different URLs in development and production.

Option|Description|Default
----- | --------- | -----
"URL"|HTTP URL|Text|none
"USERNAME"|Basic authentication user name|Text|No authentication
"PASSWORD"|Basic authentication password|Text|No password
"USER-AGENT"|User agent|Text|"HttpPost/1.0"
"CONTENT-TYPE"|Content type|Text|"application/json"
"DO-POST"|Whether to actually call the api|Boolean|true
"DO-PASSTHROUGH"|Whether to pass through the source data|Boolean|true
"BATCHSIZE"|Maximumn number rows to batch together; 0 means no batching|Integer|0
"TIMEOUT-MILLIS"|Maximum delay waiting for more rows to fill a batch (ms)|Integer|1000




