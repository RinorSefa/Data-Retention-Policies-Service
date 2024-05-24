package sample.retentionModel;

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

import sample.retentionModel.model.RetentionModelCreate;
import sample.retentionModel.model.RetentionModelUpdate;

@Component
public class RetentionModelRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        restConfiguration().bindingMode(RestBindingMode.json);

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

        rest("/retention-models")
            .post().consumes("application/json").type(RetentionModelCreate.class).to("direct:createRetentionModel")
            .get().to("direct:getRetentionModels");

        rest("/retention-models/{id}")
            .get().to("direct:getRetentionModelById")
            .put().consumes("application/json").type(RetentionModelUpdate.class).to("direct:updateRetentionModelById")
            .delete().to("direct:deleteRetentionModelById");

        from("direct:createRetentionModel")
            .to("bean-validator:RetentionModelCreate")
            .process(exchange -> {
                // Set the headers from the request body
                RetentionModelCreate retentionModelCreate = exchange.getIn().getBody(RetentionModelCreate.class);
                exchange.getIn().setHeader("name", retentionModelCreate.getName());
                exchange.getIn().setHeader("ownership", retentionModelCreate.getOwnership());
                exchange.getIn().setHeader("description", retentionModelCreate.getDescription());
                exchange.getIn().setHeader("retention_period", retentionModelCreate.getRetentionPeriod());
                exchange.getIn().setHeader("sensitive_fields", retentionModelCreate.getSensitiveFields());

                // TODO: Set the user from the security context
                exchange.getIn().setHeader("created_by", "user1");

                // Ask the SQL component to retrieve the generated keys
                exchange.getIn().setHeader("CamelSqlRetrieveGeneratedKeys", "true");
            })
            .to("sql:INSERT INTO RetentionModel (name, ownership, description, retention_period, sensitive_fields, created_by) VALUES (:#name, :#ownership, :#description, :#retention_period, :#sensitive_fields, :#created_by)")
            .process(exchange -> {
                // Retrieve the generated key from the header
                List<Map<String, Object>> generatedKeys = exchange.getMessage().getHeader(SqlConstants.SQL_GENERATED_KEYS_DATA, List.class);
                if (generatedKeys != null) {
                    // Assuming the key is in the first entry (depends on your DB and key generation strategy)
                    Object generatedKey = generatedKeys.get(0).values().iterator().next();
                    // Set the generated key in the response body
                    exchange.getMessage().setBody(generatedKey);
                }
            });

        from("direct:getRetentionModels")
            .to("sql:SELECT * FROM RetentionModel WHERE deleted_by IS NULL");

        from("direct:getRetentionModelById")
            .to("sql:SELECT * FROM RetentionModel WHERE id = :#${header.id} AND deleted_by IS NULL?outputType=SelectOne")
            .choice()
                .when(simple("${body} != null"))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .otherwise()
                    .setBody(constant("Retention model not found or deleted"))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
            .end();

        from("direct:updateRetentionModelById")
            .to("bean-validator:RetentionModelUpdate")
            .process(exchange -> {
                Integer oldId = exchange.getIn().getHeader("id", Integer.class);
                exchange.getIn().setHeader("oldId", oldId);

                RetentionModelUpdate retentionModelUpdate = exchange.getIn().getBody(RetentionModelUpdate.class);
                exchange.setProperty("retentionModelUpdate", retentionModelUpdate);
            })
            .to("sql:SELECT COUNT(*) FROM RetentionPolicy WHERE retention_model_id = :#oldId AND deleted_by IS NULL?outputType=SelectOne")
            .choice()
                .when(body().isGreaterThan(0))
                    .setBody(constant("Retention model is referenced by a policy, update not allowed"))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                    .stop()
                .otherwise()
                    .to("sql:SELECT * FROM RetentionModel WHERE id = :#oldId AND deleted_by IS NULL?outputType=SelectOne")
                    .filter(simple("${body} == null"))
                        .setBody(constant("Retention model not found or deleted"))
                        .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                        .stop()
                    .end()
                    .process(exchange -> {
                        Map<String, Object> existingModel = exchange.getIn().getBody(Map.class);
                        
                        RetentionModelUpdate retentionModelUpdate = exchange.getProperty("retentionModelUpdate", RetentionModelUpdate.class);

                        String name = retentionModelUpdate.getName() != null ? retentionModelUpdate.getName() : (String) existingModel.get("NAME");
                        String ownership = retentionModelUpdate.getOwnership() != null ? retentionModelUpdate.getOwnership() : (String) existingModel.get("OWNERSHIP");
                        String description = retentionModelUpdate.getDescription() != null ? retentionModelUpdate.getDescription() : (String) existingModel.get("DESCRIPTION");
                        Integer retentionPeriod = retentionModelUpdate.getRetentionPeriod() != null ? retentionModelUpdate.getRetentionPeriod() : (Integer) existingModel.get("RETENTION_PERIOD");
                        String sensitiveFields = retentionModelUpdate.getSensitiveFields() != null ? retentionModelUpdate.getSensitiveFields() : (String) existingModel.get("SENSITIVE_FIELDS");

                        exchange.getIn().setHeader("name", name);
                        exchange.getIn().setHeader("ownership", ownership);
                        exchange.getIn().setHeader("description", description);
                        exchange.getIn().setHeader("retention_period", retentionPeriod);
                        exchange.getIn().setHeader("sensitive_fields", sensitiveFields);
                        exchange.getIn().setHeader("created_by", "user1");
                        exchange.getIn().setHeader("CamelSqlRetrieveGeneratedKeys", "true");
                    })
                    .to("sql:INSERT INTO RetentionModel (name, ownership, description, retention_period, sensitive_fields, created_by) VALUES (:#${header.name}, :#${header.ownership}, :#${header.description}, :#${header.retention_period}, :#${header.sensitive_fields}, :#${header.created_by})")
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
                    .to("sql:UPDATE RetentionModel SET deleted_by = :#deleted_by, deleted_at = CURRENT_TIMESTAMP, updated_to_id =:#newId WHERE id = :#oldId")
                .end();

            from("direct:deleteRetentionModelById")
            .setHeader("deleted_by", constant("user2"))
            .to("sql:SELECT COUNT(*) FROM RetentionPolicy WHERE retention_model_id = :#id AND deleted_by IS NULL?outputType=SelectOne")
            .choice()
                .when(body().isGreaterThan(0))
                    .setBody(constant("Retention model is referenced by a policy, deletion not allowed"))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
                .otherwise()
                    .to("sql:UPDATE RetentionModel SET deleted_by = :#deleted_by, deleted_at = CURRENT_TIMESTAMP WHERE id = :#id AND deleted_by IS NULL")
                    .choice()
                        .when(header(SqlConstants.SQL_UPDATE_COUNT).isEqualTo(1))
                            .setBody(constant("Retention model soft deleted successfully"))
                        .when(header(SqlConstants.SQL_UPDATE_COUNT).isEqualTo(0))
                            .setBody(constant("Retention model not found or already deleted"))
                            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                        .otherwise()
                            .setBody(constant("Unexpected error occurred during deletion"))
                            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(500))
            .end();
    }
}