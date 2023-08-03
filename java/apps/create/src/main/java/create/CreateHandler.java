package create;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;

import create.daos.CustomObject;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class CreateHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  static Logger logger = LoggerFactory.getLogger(CreateHandler.class);

  private static String INPUT_S3_BUCKET_NAME;

  private final static S3Client s3Client;
  private Gson gson = new Gson();

  static {
    final String region = System.getenv(SdkSystemSetting.AWS_REGION.environmentVariable());
    final Region awsRegion = region != null ? Region.of(region) : Region.EU_WEST_1;
    s3Client = S3Client.builder()
        .httpClient(UrlConnectionHttpClient.builder().build())
        .region(awsRegion)
        .build();
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent input,
      Context context) {

    try {
      // Parse environment variables
      parseEnvVars();

      // Create the custom object
      CustomObject customObject = createCustomObject();

      // Stringify custom object
      String json = getStringOfCustomObject(customObject);

      // Store the custom object in S3
      storeObjectInS3(json);

      return createResponse(200, json);
    } catch (Exception e) {
      return createResponse(500, e.getMessage());
    }
  }

  private void parseEnvVars() {
    INPUT_S3_BUCKET_NAME = System.getenv("INPUT_S3_BUCKET_NAME");
  }

  private CustomObject createCustomObject() {
    return new CustomObject(
        "test",
        false,
        false);
  }

  private void storeObjectInS3(
      String customObjectString) {

    // Get byte array stream of string
    ByteArrayOutputStream jsonByteStream = getByteArrayOutputStream(customObjectString);

    // Prepare an InputStream from the ByteArrayOutputStream
    InputStream fis = new ByteArrayInputStream(jsonByteStream.toByteArray());

    // Put file into S3
    s3Client.putObject(
        PutObjectRequest
            .builder()
            .bucket(INPUT_S3_BUCKET_NAME)
            .key(String.valueOf(System.currentTimeMillis()))
            .build(),
        RequestBody.fromContentProvider(new ContentStreamProvider() {
          @Override
          public InputStream newStream() {
            return fis;
          }
        }, jsonByteStream.toByteArray().length, "application/json"));
  }

  private String getStringOfCustomObject(
      CustomObject customObject) {
    // Convert object to string
    return gson.toJson(customObject);
  }

  private ByteArrayOutputStream getByteArrayOutputStream(
      String data) throws RuntimeException {

    // Convert string to byte array
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(byteArrayOutputStream);
    try {
      out.write(data.getBytes());
      byteArrayOutputStream.flush();
      byteArrayOutputStream.close();
    } catch (Exception e) {
      throw new RuntimeException("getByteArrayOutputStream failed", e);
    }
    return byteArrayOutputStream;
  }

  private APIGatewayProxyResponseEvent createResponse(
      int statusCode,
      String body) {
    Map<String, String> responseHeaders = new HashMap<>();
    responseHeaders.put("Content-Type", "application/json");
    APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent().withHeaders(responseHeaders);

    return response
        .withStatusCode(statusCode)
        .withBody(body);
  }
}
