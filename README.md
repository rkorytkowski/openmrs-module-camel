Camel Module
==========================

Description
-----------
This module embeds [Apache Camel](https://camel.apache.org/) into OpenMRS, providing a powerful, enterprise-grade 
routing and mediation engine. It allows modules to easily communicate asynchronously, transform data, and integrate 
with external systems using standard Enterprise Integration Patterns (EIP).

By default, it automatically discovers any Spring beans extending Camel's `RouteBuilder` and manages their lifecycle 
alongside the OpenMRS application context.

Requirements
------------
The module requires OpenMRS Core 2.9+ and Java 17+.

Building from Source
--------------------
You will need to have Java 17+ and Maven 2.x+ installed.  Use the command 'mvn package' to 
compile and package the module.  The .omod file will be in the omod/target folder.

Installation
------------
1. Build the module to produce the .omod file.
2. Use the OpenMRS Administration > Manage Modules screen to upload and install the .omod file.

If uploads are not allowed from the web (changeable via a runtime property), you can drop the omod
into the ~/.OpenMRS/modules folder.  (Where ~/.OpenMRS is assumed to be the Application 
Data Directory that the running openmrs is currently using.)  After putting the file in there 
simply restart OpenMRS/tomcat and the module will be loaded and started.

Available Components
--------------------
This module comes pre-packaged with several essential Camel components:
* **camel-core & camel-spring**: The foundational routing engine and Spring context integration.
* **camel-jms**: Used for asynchronous messaging. The `jms` endpoint is automatically configured and available if 
* a `javax.jms.ConnectionFactory` is present in the OpenMRS Spring context.
* **camel-jackson**: Provides JSON data binding and POJO transformation capabilities.
* **camel-elasticsearch**: Enables communicating with Elasticsearch. The `elasticsearch` endpoint is automatically 
* configured using the low-level `RestClient` from OpenMRS's Hibernate Search setup (if available).

Configuration Properties
------------------------
You can configure the behavior of the Camel module by adding the following properties to your `openmrs-runtime.properties` file:

| Property | Default Value | Description |
|----------|---------------|-------------|
| `camel.autoDiscoverRoutes` | `true` | When true, automatically discovers and registers all Spring beans extending `RouteBuilder`. |
| `camel.hawtio.enabled` | `false` | Enables the embedded Hawtio web console for visualizing and managing Camel routes. |
| `camel.hawtio.host` | `127.0.0.1` | The host address the embedded Hawtio server will bind to. Defaults to loopback only. |
| `camel.hawtio.port` | `10001` | The port the embedded Hawtio server will bind to. |
| `camel.hawtio.username` | `admin` | The username required to log into the Hawtio console. |
| `camel.hawtio.password` | *(required)* | The password required to log into the Hawtio console. Must be set when `camel.hawtio.enabled=true`. |

Hawtio Web Console
------------------
Hawtio is an open-source, modular web console for managing your Java applications. This module allows you to spin up 
a standalone Hawtio instance directly from OpenMRS. 

To turn it on, set `camel.hawtio.enabled=true` in your `openmrs-runtime.properties`. Once OpenMRS starts, you can 
navigate to `http://localhost:10001/hawtio` and log in using the credentials you configured via
`camel.hawtio.username` (default `admin`) and `camel.hawtio.password` to view route metrics, trace messages,
and manage your integration endpoints.

Creating a Route
----------------
To create a Camel route from your own custom OpenMRS module, simply create a Spring `@Component` that extends Camel's 
`RouteBuilder`. Because `camel.autoDiscoverRoutes` is enabled by default, the Camel module will detect it, register the 
route, and start it automatically.

```java
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

@Component
public class MySampleRoute extends RouteBuilder {
    
    @Override
    public void configure() throws Exception {
        // Example: Read messages from a JMS queue, process them, and log the output
        from("jms:queue:my-custom-queue")
            .routeId("my-custom-route")
            .log("Received a new message: ${body}");
    }
}
```
For a more complete example see [`PatientSummaryRoute`](https://github.com/openmrs/openmrs-module-camel/blob/main/api/src/test/java/org/openmrs/module/route/PatientSummaryRoute.java) and [`PatientSummaryRouteIntegrationTest`](https://github.com/openmrs/openmrs-module-camel/blob/main/api/src/test/java/org/openmrs/module/route/PatientSummaryRouteIntegrationTest.java) in the api/src/test/java dir.
