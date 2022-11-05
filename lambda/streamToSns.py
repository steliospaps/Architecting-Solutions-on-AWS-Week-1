import boto3, json, os

sns = os.environ['SNS_ARN']


client = boto3.client('sns')

def lambda_handler(event, context):

    for record in event["Records"]:

        if record['eventName'] == 'INSERT':
            new_record = record['dynamodb']['NewImage']    
            response = client.publish(
                TargetArn=sns,
                Message=json.dumps({'default': json.dumps(new_record)}),
                MessageStructure='json'
            )