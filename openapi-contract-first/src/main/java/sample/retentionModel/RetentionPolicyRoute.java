package sample.retentionModel;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.bean.validator.BeanValidationException;
import org.apache.camel.component.sql.SqlConstants;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParseException;

import sample.retentionModel.policy.RetentionPolicyCreate;
import sample.retentionModel.policy.RetentionPolicyUpdate;

@Component
public class RetentionPolicyRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        onException(BeanValidationException.class)
            .handled(true)
            .log(LoggingLevel.ERROR, "Validation failed for the request: ${exception.message}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(exchange -> {
                BeanValidationException exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, BeanValidationException.class);
                StringBuilder errorMessage = new StringBuilder("Validation failed: ");
                exception.getConstraintViolations().forEach(violation ->
                    errorMessage.append(violation.getPropertyPath()).append(" ").append(violation.getMessage()).append("; "));
                return errorMessage.toString();
            })            
            .stop()
        .end();

                // Specific exception handlers
        onException(JsonParseException.class)
            .handled(true)
            .log(LoggingLevel.ERROR, "Invalid JSON data provided for the request: ${exception.message}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody().simple("{\"error\": \"Invalid JSON data provided for the request.\", \"details\": \"${exception.message}\", \"suggestion\": \"Please check the request body for correct JSON format and try again.\"}")
            .end();

        onException(SQLException.class)
            .handled(true)
            .log(LoggingLevel.ERROR, "Database error: ${exception.message}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody().simple("{\"error\": \"An error occurred while processing your request.\", \"suggestion\": \"Please try again later. Our developers have been notified of this issue.\"}")
            .end();

        // General exception handler
        onException(Exception.class)
            .handled(true)
            .log(LoggingLevel.ERROR, "Unexpected error: ${exception.message}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody().simple("{\"error\": \"An error occurred while processing your request.\", \"suggestion\": \"Please try again later. Our developers have been notified of this issue.\"}")
            .end();

        restConfiguration()
            .bindingMode(RestBindingMode.json)
            .clientRequestValidation(true);

        rest("/retention_policies")
            .post().consumes("application/json").type(RetentionPolicyCreate.class).to("direct:createRetentionPolicy")
            .get().to("direct:getRetentionPolicies");

        rest("/retention_policies/{id}")
            .get().to("direct:getRetentionPolicyById")
            .put().consumes("application/json").type(RetentionPolicyUpdate.class).to("direct:updateRetentionPolicyById")
            .delete().to("direct:deleteRetentionPolicyById");

        rest("/retention_policies/byTenant/{tenant}")
            .get().to("direct:getRetentionPoliciesByTenant");

        from("direct:createRetentionPolicy")
            .to("bean-validator:validateRetentionPolicyCreate")
            .process(exchange -> {
                RetentionPolicyCreate retentionPolicyCreate = exchange.getMessage().getBody(RetentionPolicyCreate.class);

                exchange.getIn().setHeader("retention_model_id", retentionPolicyCreate.getRetentionModelId());
                exchange.getIn().setHeader("retention_period", retentionPolicyCreate.getRetentionPeriod());
                exchange.getIn().setHeader("action", retentionPolicyCreate.getAction());
                exchange.getIn().setHeader("tenant", retentionPolicyCreate.getTenant());
                exchange.getIn().setHeader("created_by", "user1");
                exchange.getIn().setHeader("CamelSqlRetrieveGeneratedKeys", true);
            })
            .to("sql:SELECT retention_period FROM RetentionModel WHERE id = :#retention_model_id?outputType=SelectOne")
            .process(exchange -> {
                Integer result = exchange.getMessage().getBody(Integer.class);
                if (result == null) {
                    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
                    exchange.getIn().setBody("Retention model does not exist or is deleted");
                    return;
                }

                if (exchange.getIn().getHeader("retention_period") == null) {
                    exchange.getIn().setHeader("retention_period", result);
                }
            })
            .choice()
                .when(header(Exchange.HTTP_RESPONSE_CODE).isEqualTo(400))
                    .stop()
                .otherwise()
                    .to("sql:INSERT INTO RetentionPolicy (retention_model_id, retention_period, action, tenant, created_by) VALUES (:#retention_model_id, :#retention_period, :#action, :#tenant, :#created_by)")
                    .process(exchange -> {
                        List<Map<String, Object>> generatedKeys = exchange.getMessage().getHeader(SqlConstants.SQL_GENERATED_KEYS_DATA, List.class);
                        if (generatedKeys != null && !generatedKeys.isEmpty()) {
                            Object generatedKey = generatedKeys.get(0).values().iterator().next();
                            exchange.getMessage().setBody(generatedKey);
                        }
                    });

        from("direct:getRetentionPolicies")
            .to("sql:SELECT * FROM RetentionPolicy WHERE deleted_by IS NULL");

        from("direct:getRetentionPoliciesByTenant")
            .process(exchange -> {
                String tenant = exchange.getIn().getHeader("tenant", String.class);
                String decodedTenant = java.net.URLDecoder.decode(tenant, StandardCharsets.UTF_8.name());
                exchange.getIn().setHeader("tenant", decodedTenant);
            })
            .to("sql:SELECT * FROM RetentionPolicy WHERE tenant = :#${header.tenant} AND deleted_by IS NULL");

        from("direct:getRetentionPolicyById")
            .to("sql:SELECT * FROM RetentionPolicy WHERE id = :#${header.id} AND deleted_by IS NULL?outputType=SelectOne")
            .choice()
                .when(simple("${body} != null"))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .otherwise()
                    .setBody(constant("Retention policy not found or deleted"))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
            .end();

        from("direct:updateRetentionPolicyById")
            .to("bean-validator:validateRetentionPolicyUpdate")
            .process(exchange -> {
                Integer oldId = exchange.getIn().getHeader("id", Integer.class);
                exchange.getIn().setHeader("oldId", oldId);

                RetentionPolicyUpdate retentionPolicyUpdate = exchange.getIn().getBody(RetentionPolicyUpdate.class);
                exchange.setProperty("retentionPolicyUpdate", retentionPolicyUpdate);
            })
            .to("sql:SELECT * FROM RetentionPolicy WHERE id = :#oldId AND deleted_by IS NULL?outputType=SelectOne")
            .filter(simple("${body} == null"))
                .setBody(constant("Retention policy not found or deleted"))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                .stop()
            .end()
            .process(exchange -> {
                Map<String, Object> existingPolicy = exchange.getIn().getBody(Map.class);

                RetentionPolicyUpdate retentionPolicyUpdate = exchange.getProperty("retentionPolicyUpdate", RetentionPolicyUpdate.class);

                Integer retention_model_id = retentionPolicyUpdate.getRetentionModelId() != null ? retentionPolicyUpdate.getRetentionModelId() : (Integer) existingPolicy.get("RETENTION_MODEL_ID");
                Integer retention_period = retentionPolicyUpdate.getRetentionPeriod() != null ? retentionPolicyUpdate.getRetentionPeriod() : (Integer) existingPolicy.get("RETENTION_PERIOD");
                String action = retentionPolicyUpdate.getAction() != null ? retentionPolicyUpdate.getAction() : (String) existingPolicy.get("ACTION");
                String tenant = (String) existingPolicy.get("TENANT");

                exchange.getIn().setHeader("retention_model_id", retention_model_id);
                exchange.getIn().setHeader("retention_period", retention_period);
                exchange.getIn().setHeader("action", action);
                exchange.getIn().setHeader("tenant", tenant);
                exchange.getIn().setHeader("created_by", "user1");
                exchange.getIn().setHeader("CamelSqlRetrieveGeneratedKeys", "true");
            })
            .to("sql:INSERT INTO RetentionPolicy (retention_model_id, retention_period, action, tenant, created_by) VALUES (:#${header.retention_model_id}, :#${header.retention_period}, :#${header.action}, :#${header.tenant}, :#${header.created_by})")
            .process(exchange -> {
                List<Map<String, Object>> generatedKeys = exchange.getMessage().getHeader(SqlConstants.SQL_GENERATED_KEYS_DATA, List.class);
                if (generatedKeys != null) {
                    Object generatedKey = generatedKeys.get(0).values().iterator().next();
                    exchange.getMessage().setBody(generatedKey);
                }
            })
            .process(exchange -> {
                exchange.getIn().setHeader("newid", exchange.getMessage().getBody(Integer.class));
                exchange.getIn().setHeader("deleted_by", "user1");
            })
            .to("sql:UPDATE RetentionPolicy SET deleted_by = :#deleted_by, deleted_at = CURRENT_TIMESTAMP, updated_to_id =:#newId WHERE id = :#oldId");

        from("direct:deleteRetentionPolicyById")
            .setHeader("deleted_by", constant("user1"))
            .to("sql:UPDATE RetentionPolicy SET deleted_by = :#deleted_by, deleted_at = CURRENT_TIMESTAMP WHERE id = :#id AND deleted_by IS NULL")
            .choice()
                .when(header(SqlConstants.SQL_UPDATE_COUNT).isEqualTo(1))
                    .setBody(constant("Retention policy soft deleted successfully"))
                .when(header(SqlConstants.SQL_UPDATE_COUNT).isEqualTo(0))
                    .setBody(constant("Retention policy not found or already deleted"))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                .otherwise()
                    .setBody(constant("Unexpected error occurred during deletion"))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
            .end();
    }
}
