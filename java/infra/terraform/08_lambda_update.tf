##############
### Lambda ###
##############

# IAM role for Lambda
resource "aws_iam_role" "lambda_update_iam" {
  name               = local.lambda_update_iam_role_name
  assume_role_policy = data.aws_iam_policy_document.assume_role_lambda.json
}

# IAM policy attachment for Lambda to have full S3 access
resource "aws_iam_role_policy_attachment" "lambda_update_s3_full_access" {
  role       = aws_iam_role.lambda_update_iam.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonS3FullAccess"
}

# IAM policy attachment for Lambda to have full SQS access
resource "aws_iam_role_policy_attachment" "lambda_update_sqs_full_access" {
  role       = aws_iam_role.lambda_update_iam.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSQSFullAccess"
}

# Cloudwatch log group for Lambda
resource "aws_cloudwatch_log_group" "lambda_update" {
  name              = "/aws/lambda/${local.lambda_update_function_name}"
  retention_in_days = 7
}

# IAM policy for Lambda logging
resource "aws_iam_policy" "lambda_update_logging" {
  name        = "java_lambda_update_logging"
  path        = "/"
  description = "IAM policy for logging from a lambda"
  policy      = data.aws_iam_policy_document.lambda_logging.json
}

# IAM policy attachment for Lambda to log to CloudWatch
resource "aws_iam_role_policy_attachment" "lambda_update_logging" {
  role       = aws_iam_role.lambda_update_iam.name
  policy_arn = aws_iam_policy.lambda_update_logging.arn
}

# Lambda layer for collector config
resource "aws_lambda_layer_version" "update_collector_config" {
  filename         = local.lambda_update_collector_config_zip_path
  layer_name       = "update_collector_config"
  source_code_hash = filebase64sha256(local.lambda_update_collector_config_zip_path)
}

# Lambda function
resource "aws_lambda_function" "update" {
  filename      = local.lambda_update_function_jar_file_path
  function_name = local.lambda_update_function_name

  role    = aws_iam_role.lambda_update_iam.arn
  handler = "update.UpdateHandler::handleRequest"

  source_code_hash = filebase64sha256(local.lambda_update_function_jar_file_path)

  runtime     = "java11"
  timeout     = 10
  memory_size = 512

  layers = [
    "arn:aws:lambda:${var.AWS_REGION}:901920570463:layer:aws-otel-java-wrapper-amd64-ver-1-28-0:1",
    aws_lambda_layer_version.update_collector_config.arn,
  ]

  environment {
    variables = {
      # AWS_LAMBDA_EXEC_WRAPPER             = "/opt/otel-handler"
      # OPENTELEMETRY_COLLECTOR_CONFIG_FILE = "/opt/collector.yaml"
      # OTEL_EXPORTER_OTLP_ENDPOINT         = "http://localhost:4317"
      # OTEL_METRICS_EXPORTER               = "otlp"
      NEWRELIC_OTLP_ENDPOINT = substr(var.NEWRELIC_LICENSE_KEY, 0, 2) == "eu" ? "otlp.eu01.nr-data.net:4317" : "otlp.nr-data.net:4317"
      NEWRELIC_LICENSE_KEY   = var.NEWRELIC_LICENSE_KEY
      OUTPUT_S3_BUCKET_NAME  = aws_s3_bucket.output.id
      SQS_QUEUE_URL          = aws_sqs_queue.queue.url
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.lambda_update_logging,
    aws_cloudwatch_log_group.lambda_update,
  ]
}

# Lambda permission for S3 to invoke
resource "aws_lambda_permission" "allow_s3_bucket_for_update" {
  statement_id  = "AllowExecutionFromS3Bucket"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.update.arn
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.input.arn
}

# S3 trigger for Lambda
resource "aws_s3_bucket_notification" "update_s3_trigger" {
  bucket = aws_s3_bucket.input.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.update.arn
    events              = ["s3:ObjectCreated:*"]
  }

  depends_on = [
    aws_lambda_permission.allow_s3_bucket_for_update
  ]
}
