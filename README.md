# Event Data Heartbeat

The operation of Event Data is reported via JSON-format status endpoints, which are monitored by Pingdom. The configuration of the status pages is data-driven, meaning that a number of endpoints can be provided, each doing a particular job. 

This service is completely configuration-driven, but it is designed to serve up the following:

 - https://agents-heartbeat.eventdata.crossref.org/heartbeat
 - https://quality-heartbeat.eventdata.crossref.org/heartbeat

This service loads an Artifact (chosen through configuration) which contains the checks, represented as a set of rules. It starts an HTTP server, exposing the /heatbeat endpoint. There are two kinds of checks: benchmarks and url-checks. The status of each check is reported in the response. If all checks are successful, the stop-level status of "OK" is returend, and the HTTP status code is 200. If one or more status checks is not succesful, then "OK" is not returned and an HTTP status of 503 is used.

In addition to this, the HTTP endpoint allows specific rules can be selected, meaning you can single out a specific check. E.g. this checks the two streaming sources:

 - https://agents-heartbeat.eventdata.crossref.org/heartbeat?benchmarks=agent-twitter-send-batch,agent-wikipedia-send-batch


## Benchmarks

Monitor the Evidence Log Kafka topic (which records the live behaviour of the Agents and Percolator). Each rule specifies a rule to match the Evidence Log and a window, in milliseconds, in which we expect to see a message. Here's an example fragment of an Artifact containing a benchmark rule:

  {
    "benchmarks": [

        ...

      {
        "name": "percolator-event",
        "description": "Percolator creates an Event.",
        "matcher": {
          "i": "p000c"
        },
        "comment": "Percolator should regularly create Events.",
        "target": 10000
      }

      ...
    ]
  }

For example, this rule says:

For the rule named 'percolator-event', we expect to see a log entry at least every 10 seconds with the type of "p000c". For more detail on Evidence Log types, see the Event Data User Guide.

The Event Data Heartbeat service monitors the Kafka topic live, and stores an internal state. Every time the endpoint URL is visitedd, it will compare the most recent timestamp for each rule with the current time. 

A bit of extra time padding should be added to allow for clock drift. Also, due to the robst decoupled design of e.g. the Percolator, rules should be lenient enough to allow the Percolator to wait for a while before processing data. Finding the right targets is a question of empirical observation of a happily running system.

## URL Checks

A set of templated URLs can be provided. They can have templated slots for dates. Whenever the /heartbeat endpoint is queried, each of the URLs will be checked for existence.

A number of context variables (at the time of writing, one) is provided. As the functionality expands, more of these may be included.

 - `yesterday` - Yesterday's date in YYYY-MM-DD format. Because this is used to monitor daily snapshots, which can take a while to complete, six hours of grace time is included. So at `2018-01-10T05:00` this will show `2018-01-08` and at `2018-01-10T07:00` this will show `2018-01-09`.

  {
    "urls": [

        ...

      {
        "name": "evidence-log-json",
        "description": "Yesterday's Evidence Log should be present.",
        "url": "https://evidence.eventdata.crossref.org/log/{{yesterday}}.txt",
        "method": "HEAD",
        "comment": "Daily Evidence Log should be present."
      }

      ...
    ]
  }



## Configuration

Unlike other services, this will run in different places with different configurations. Be sure to check the per-service environment config in the Docker file.

 - GLOBAL_STATUS_TOPIC - Evidence Log Kafka topic
 - GLOBAL_KAFKA_BOOTSTRAP_SERVERS - Kafka connection string
 - HEARTBEAT_ARTIFACT - Artifact id to monitor
 - HEARTBEAT_PORT - HTTP port to serve on
 - GLOBAL_ARTIFACT_URL_BASE - e.g. https://artifact.eventdata.crossref.org

Note that the legacy name 'status' was the original name for 'Evidence Log'.

## License

Copyright © 2018 Crossref

Licensed under MIT license, see LICENSE file.

