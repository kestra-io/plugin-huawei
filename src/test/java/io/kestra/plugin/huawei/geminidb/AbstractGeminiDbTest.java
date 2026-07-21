package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.util.UUID;

/**
 * Base class for GeminiDB (DynamoDB-compatible API) task tests.
 *
 * <p>GeminiDB for NoSQL ships a DynamoDB-compatible data-plane API, so these tests run against a
 * local DynamoDB Local container (started in CI via {@code docker-compose-ci.yml}, or locally with
 * {@code docker compose -f docker-compose-ci.yml up -d dynamodb-local}), using the exact same AWS
 * SDK v2 transport the production tasks use, pointed at a local endpoint via {@code endpointOverride}.
 * No live Huawei Cloud account is required, so no env gate applies to these tests.
 *
 * <p>Override the target with {@code GEMINIDB_TEST_ENDPOINT} (and {@code GEMINIDB_TEST_ACCESS_KEY} /
 * {@code GEMINIDB_TEST_SECRET_KEY} / {@code GEMINIDB_TEST_REGION}) to point at a real GeminiDB instance.
 */
@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractGeminiDbTest {

    static final String TEST_ENDPOINT = env("GEMINIDB_TEST_ENDPOINT", "http://localhost:8000");
    static final String TEST_AK = env("GEMINIDB_TEST_ACCESS_KEY", "test");
    static final String TEST_SK = env("GEMINIDB_TEST_SECRET_KEY", "test");
    static final String TEST_REGION = env("GEMINIDB_TEST_REGION", "cn-north-1");

    @Inject
    RunContextFactory runContextFactory;

    /** Raw client used for seeding and verification, bypassing task code. */
    DynamoDbClient rawClient;

    /** Per-subclass table, initialised by {@link #setUpTable()}. */
    String testTableName;

    @BeforeAll
    void setUpTable() {
        rawClient = buildRawClient();
        var suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        testTableName = "kestra_geminidb_" + getClass().getSimpleName().toLowerCase() + "_" + suffix;

        try {
            rawClient.createTable(builder -> builder
                .tableName(testTableName)
                .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST));
        } catch (ResourceInUseException e) {
            // table already exists — fine on a shared local instance
        }
    }

    @AfterAll
    void tearDownTable() {
        if (rawClient == null) {
            return;
        }
        try {
            rawClient.deleteTable(builder -> builder.tableName(testTableName));
        } catch (Exception ignored) {
            // best-effort cleanup; DynamoDB Local is ephemeral anyway
        } finally {
            rawClient.close();
        }
    }

    static DynamoDbClient buildRawClient() {
        return DynamoDbClient.builder()
            .endpointOverride(URI.create(TEST_ENDPOINT))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(TEST_AK, TEST_SK)))
            .region(Region.of(TEST_REGION))
            .build();
    }

    /**
     * Applies the configured connection settings to any GeminiDB task builder that extends
     * {@link AbstractGeminiDb}.
     *
     * <p>The raw-type cast is intentional: Lombok's fluent builder chains require the exact concrete
     * builder type, and the generic bound cannot express that constraint. Safe here because we only
     * set inherited {@link AbstractGeminiDb} properties that all concrete builders accept.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <B> B applyGeminiDbConfig(B builder) {
        var b = (AbstractGeminiDb.AbstractGeminiDbBuilder) builder;
        b.accessKeyId(Property.ofValue(TEST_AK));
        b.secretAccessKey(Property.ofValue(TEST_SK));
        b.endpoint(Property.ofValue(TEST_ENDPOINT));
        b.region(Property.ofValue(TEST_REGION));
        b.tableName(Property.ofValue(testTableName));
        return (B) b;
    }

    private static String env(String name, String defaultValue) {
        var v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
