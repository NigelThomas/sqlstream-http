-- testpost.sql
-- 
-- Basic test of the HTTP Post

CREATE OR REPLACE SCHEMA "testpost";
SET SCHEMA '"testpost"';
SET PATH '"testpost"';

CREATE OR REPLACE JAR "sqlstream-http"
    LIBRARY 'file:/home/vagrant/nigel/Dropbox/github/sqlstream-http/sqlstream-http.jar'
    OPTIONS(0);


-- create a function for the HttpPost UDX

CREATE OR REPLACE FUNCTION "HttpPost"
( inputRows CURSOR
, optionRows CURSOR                         
, "postDataColumnName" VARCHAR(128)
) RETURNS TABLE
( inputRows.*
, "httpResult" INTEGER
, "httpResponse" VARCHAR(1000)
)
LANGUAGE JAVA
PARAMETER STYLE SYSTEM DEFINED JAVA
NO SQL
EXTERNAL NAME '"testpost"."sqlstream-http":com.sqlstream.utilities.http.HttpPost.httpPost';

CREATE OR REPLACE STREAM "requests"
( "postData" VARCHAR(1000)
);


CREATE OR REPLACE VIEW "HttpPostOptions"
( "option", "value")
AS 
SELECT * FROM 
(VALUES('URL', 'https://postman-echo.com/post')
      ,('DO-POST', 'TRUE')
      ,('DO-PASSTHROUGH', 'TRUE')
);  

CREATE OR REPLACE VIEW "postResponses"
AS
SELECT STREAM *
FROM STREAM(
    "HttpPost"
        ( CURSOR (SELECT STREAM * FROM "requests")
        , CURSOR (SELECT * FROM "HttpPostOptions")
        , 'postData'
        )
);

