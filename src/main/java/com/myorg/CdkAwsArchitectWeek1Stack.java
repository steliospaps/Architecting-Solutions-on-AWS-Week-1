package com.myorg;

import software.constructs.Construct;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import software.amazon.awscdk.Aws;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.AwsIntegration;
import software.amazon.awscdk.services.apigateway.ContentHandling;
import software.amazon.awscdk.services.apigateway.EndpointConfiguration;
import software.amazon.awscdk.services.apigateway.Integration;
import software.amazon.awscdk.services.apigateway.IntegrationOptions;
import software.amazon.awscdk.services.apigateway.IntegrationResponse;
import software.amazon.awscdk.services.apigateway.IntegrationType;
import software.amazon.awscdk.services.apigateway.Method;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.MethodResponse;
import software.amazon.awscdk.services.apigateway.PassthroughBehavior;
import software.amazon.awscdk.services.apigateway.Resource;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.StreamViewType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.events.targets.ApiGateway;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.EventSourceMapping;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.StartingPosition;
import software.amazon.awscdk.services.lambda.eventsources.DynamoEventSource;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.ITopicSubscription;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.amazon.awscdk.services.sqs.Queue;
// import software.amazon.awscdk.Duration;
// import software.amazon.awscdk.services.sqs.Queue;

public class CdkAwsArchitectWeek1Stack extends Stack {
    public CdkAwsArchitectWeek1Stack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    @SuppressWarnings("serial")
    public CdkAwsArchitectWeek1Stack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        ManagedPolicy writeDynamoDbPolicy = ManagedPolicy.Builder.create(this, "lambda-write-dynamodb")
                .statements(Arrays
                        .asList(PolicyStatement.Builder.create().effect(Effect.ALLOW).resources(Arrays.asList("*"))
                                .actions(Arrays.asList("dynamodb:PutItem", "dynamodb:DescribeTable"))

                                .build()))
                .build();

        ManagedPolicy publishSnsPolicy = ManagedPolicy.Builder.create(this, "lambda-sns-publish")
                .statements(Arrays
                        .asList(PolicyStatement.Builder.create().effect(Effect.ALLOW).resources(Arrays.asList("*"))
                                .actions(Arrays.asList("sns:Publish", "sns:GetTopicAttributes", "sns:ListTopics"))

                                .build()))
                .build();

        ManagedPolicy readDynamoDbStreamPolicy = ManagedPolicy.Builder.create(this, "lambda-read-dynamodb-stream")
                .statements(Arrays
                        .asList(PolicyStatement.Builder.create().effect(Effect.ALLOW).resources(Arrays.asList("*"))
                                .actions(Arrays.asList("dynamodb:GetShardIterator", "dynamodb:DescribeStream",
                                        "dynamodb:ListStreams", "dynamodb:GetRecords"))

                                .build()))
                .build();

        ManagedPolicy readSqsPolicy = ManagedPolicy.Builder.create(this, "lambda-read-sqs")
                .statements(Arrays
                        .asList(PolicyStatement.Builder.create().effect(Effect.ALLOW).resources(Arrays.asList("*"))
                                .actions(Arrays.asList("sqs:DeleteMessage", "sqs:ReceiveMessage",
                                        "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"))

                                .build()))
                .build();

        Role lambdaSqsDynDbRole = Role.Builder.create(this, "lambda-sqs-dynamodb")
                .assumedBy(ServicePrincipal.Builder.create("lambda.amazonaws.com").build())
                .managedPolicies(Arrays.asList(readSqsPolicy, writeDynamoDbPolicy)).build();

        Role lambdaDynDbStreamToSnsRole = Role.Builder.create(this, "lambda-dynamo-db-stream-sns")
                .assumedBy(ServicePrincipal.Builder.create("lambda.amazonaws.com").build())
                .managedPolicies(Arrays.asList(publishSnsPolicy, readDynamoDbStreamPolicy)).build();

        Role apiGwRole = Role.Builder.create(this, "APIGateway-SQS")
                .assumedBy(ServicePrincipal.Builder.create("apigateway.amazonaws.com").build())
                .managedPolicies(Arrays.asList(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonAPIGatewayPushToCloudWatchLogs")))
                .build();

        Table dbTable = Table.Builder.create(this, "POC-Table")
                .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
                .removalPolicy(RemovalPolicy.DESTROY)//
                .stream(StreamViewType.NEW_IMAGE).build();

        dbTable.grantWriteData(lambdaSqsDynDbRole);

        Function lambdaSqsToDb = Function.Builder.create(this, "sqsToDb")//
                .logRetention(RetentionDays.TWO_WEEKS)//
                .runtime(Runtime.PYTHON_3_9)//
                .handler("sqsToDb.lambda_handler")//
                .code(Code.fromAsset("lambda"))//
                .role(lambdaSqsDynDbRole)//
                .environment(new HashMap<String, String>() {
                    {
                        put("TABLE", dbTable.getTableName());
                    }
                }).build();
        
        

        Queue queue = Queue.Builder.create(this, "POC-Queue").visibilityTimeout(Duration.seconds(300)).build();

        lambdaSqsToDb .addEventSource(new SqsEventSource(queue));
        
        queue.grantConsumeMessages(lambdaSqsDynDbRole);
        queue.grantSendMessages(apiGwRole);

        Topic sns = Topic.Builder.create(this, "POC-topic").build();

        sns.grantPublish(lambdaDynDbStreamToSnsRole);

        sns.addSubscription(EmailSubscription.Builder.create("stelios05@gmail.com").build());

        Function lambdaDynDbStreamToSns = Function.Builder.create(this, "streamToSns")//
                .logRetention(RetentionDays.TWO_WEEKS)//
                .runtime(Runtime.PYTHON_3_9)//
                .handler("streamToSns.lambda_handler")//
                .code(Code.fromAsset("lambda"))//
                .role(lambdaDynDbStreamToSnsRole)//
                .environment(new HashMap<String, String>() {
                    {
                        put("SNS_ARN",sns.getTopicArn());
                    }
                }).build();
        
        lambdaDynDbStreamToSns.addEventSource(DynamoEventSource//
                .Builder//
                .create(dbTable)//
                .startingPosition(StartingPosition.TRIM_HORIZON)//
                .build());
       

        
        RestApi restApi = RestApi.Builder//
                .create(this, "restApi")
                .build();
        ApiGateway apiGw = ApiGateway.Builder.create(restApi)//
        
        .build();
        
        
        Resource orderMethod = apiGw.getRestApi().getRoot().addResource("order");
        
        
        Method orderPost = orderMethod.addMethod("POST",AwsIntegration.Builder.create()//
                .integrationHttpMethod("POST")//
                .path("/"+Aws.ACCOUNT_ID+"/"+queue.getQueueName())//
                .region(Aws.REGION)//
                .service("sqs")//
                .options( IntegrationOptions.builder()//
                        .passthroughBehavior(PassthroughBehavior.NEVER)//
                        .requestParameters(new HashMap<String,String>(){{ //
                            put("integration.request.header.Content-Type","'application/x-www-form-urlencoded'");
                        }})
                        .requestTemplates(new HashMap<String,String>(){{// 
                            put("application/json", "Action=SendMessage&MessageBody=$input.body");
                        }})
                        
                        .integrationResponses(Arrays.asList(IntegrationResponse.builder()//
                                .statusCode("200")
                                
                                .responseTemplates(new HashMap<String,String>() {{
                                    put("application/json", "Action=SendMessage&MessageBody=$input.body");
                                }})
                                .build())) 
                        .credentialsRole(apiGwRole)//
                        .build())
                .build(),//
                MethodOptions.builder()
                .methodResponses(Arrays.asList(MethodResponse.builder()//
                        .statusCode("200")//
                        .build()))
                .build());
        
    }
}
