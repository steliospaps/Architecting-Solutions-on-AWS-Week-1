import boto3, uuid, os

tableName = os.environ['TABLE']
client = boto3.resource('dynamodb')
table = client.Table(tableName)

def lambda_handler(event, context):
    for record in event['Records']:
        print("test")
        payload = record["body"]
        print(str(payload))
        table.put_item(Item= {'PK': "order#"+str(uuid.uuid4()),'order':  payload})