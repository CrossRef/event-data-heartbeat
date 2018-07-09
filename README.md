# Event Data Heartbeat

Monitor the Event Bus for expected benchmarks, serve up a status page that can be monitored externally. It does not replace monitoring and analytics; instead it gives unambiguous headline-level 'is the service working?' status indications that can be monitored. 

This service monitors the Evidence Log Kafka topic. It's intended to be used in three places:

 - Agents
 - Quality Checks
 - Event Bus

In each instances a JSON document with KPIs is loaded from the Artifact registry. This means that this service can be configured to monitor different parts of the system. 

Each document contains a list of rules, which specify a matcher filter log messages and a target, specified in milliseconds. The timestamp is attached to the message when it is logged. Clock drift shouldn't be a problem as long as suitable margins are incldued in the targets.

See doc/example-benchmarks-artifact.json for an example.

## Configuration

Unlike other services, this will run in different places with different configurations. Be sure to check the per-service environment config in the Docker file.

 - GLOBAL_STATUS_TOPIC - Evidence Log Kafka topic
 - GLOBAL_KAFKA_BOOTSTRAP_SERVERS - Kafka connection string
 - HEARTBEAT_ARTIFACT - Artifact id to monitor
 - HEARTBEAT_PORT - HTTP port
 - GLOBAL_ARTIFACT_URL_BASE

Note that the legacy name 'status' was the original name for 'Evidence Log'.

## License

Copyright © 2018 Crossref

Licensed under MIT license, see LICENSE file.