# Shopping Cart Service

## Description
Based on a template provided by Lightbend. This service together with shopping-order-service and shopping-analytics-service forms an application responsible for simulating a shopping cart. All the shopping cart logic is implemented by this service, with ordering done by gRPC calls to the shopping-ordering-service and analytics provided to shopping-analytics-service with Akka Projections and Kafka. 

## Running the sample code

1. Start a first node:

    ```
    sbt -Dconfig.resource=local1.conf run
    ```

2. (Optional) Start another node with different ports:

    ```
    sbt -Dconfig.resource=local2.conf run
    ```

3. Check for service readiness

    ```
    curl http://localhost:9101/ready
    ```
