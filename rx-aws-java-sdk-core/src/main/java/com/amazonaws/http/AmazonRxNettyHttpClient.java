package com.amazonaws.http;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import rx.Observable;

import io.netty.buffer.ByteBuf;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.protocol.http.AbstractHttpConfigurator;
import io.reactivex.netty.protocol.http.server.HttpServerRequest;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import io.reactivex.netty.protocol.http.server.HttpResponseHeaders;
import io.reactivex.netty.protocol.http.client.HttpClient;
import io.reactivex.netty.protocol.http.client.HttpClient.HttpClientConfig;
import io.reactivex.netty.protocol.http.client.HttpClientRequest;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import io.reactivex.netty.protocol.http.client.HttpClientPipelineConfigurator;
import io.reactivex.netty.protocol.http.client.HttpRequestHeaders;
import io.reactivex.netty.pipeline.PipelineConfigurator;
import io.reactivex.netty.pipeline.PipelineConfiguratorComposite;
import io.reactivex.netty.pipeline.ssl.DefaultFactories;

import org.w3c.dom.Node;

import com.amazonaws.*;
import com.amazonaws.auth.*;
import com.amazonaws.event.*;
import com.amazonaws.handlers.*;
import com.amazonaws.http.*;
import com.amazonaws.internal.*;
import com.amazonaws.metrics.*;
import com.amazonaws.regions.*;
import com.amazonaws.transform.*;
import com.amazonaws.util.*;
import com.amazonaws.util.AWSRequestMetrics.Field;

import org.joda.time.format.ISODateTimeFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import javax.xml.bind.DatatypeConverter;

import java.security.MessageDigest;


abstract public class AmazonRxNettyHttpClient extends AmazonWebServiceClient {

  private static final String HMAC_SHA_256 = "HmacSHA256";
  private static final String SHA_256 = "SHA-256";
  private static final Mac MAC_HMAC_SHA_256;
  private static final MessageDigest MESSAGE_DIGEST_SHA_256;
  private static final Map<String,HttpClient<ByteBuf,ByteBuf>> CLIENTS =
    new ConcurrentHashMap<String,HttpClient<ByteBuf,ByteBuf>>();

  protected String mkToken(String... tokens) {
    if (tokens.length == 1)
      return tokens[0];
    else if (Arrays.stream(tokens).anyMatch(t -> { return t != null; }))
      return Arrays.stream(tokens).reduce((s1, s2) -> s1 + "|" + s2).get();
    else
      return null;
  }

  static {
    try {
      MAC_HMAC_SHA_256 = Mac.getInstance(HMAC_SHA_256);
      MESSAGE_DIGEST_SHA_256 = MessageDigest.getInstance(SHA_256);
    }
    catch (java.security.NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  private AWSCredentialsProvider awsCredentialsProvider;

  public AmazonRxNettyHttpClient() {
    this(new DefaultAWSCredentialsProviderChain(), new ClientConfiguration());
  }

  public AmazonRxNettyHttpClient(AWSCredentialsProvider awsCredentialsProvider) {
    this(awsCredentialsProvider, new ClientConfiguration());
  }

  public AmazonRxNettyHttpClient(ClientConfiguration clientConfiguration) {
    this(new DefaultAWSCredentialsProviderChain(), clientConfiguration);
  }

  public AmazonRxNettyHttpClient(
    AWSCredentialsProvider awsCredentialsProvider,
    ClientConfiguration clientConfiguration
  ) {
    super(clientConfiguration);
    this.awsCredentialsProvider = awsCredentialsProvider;
    init();
  }

  abstract protected void init();

  private byte[] hmacSHA256(String data, byte[] key) {
    try {
      Mac mac = (Mac) MAC_HMAC_SHA_256.clone();
      mac.init(new SecretKeySpec(key, HMAC_SHA_256));
      return mac.doFinal(data.getBytes("UTF8"));
    }
    catch (java.lang.CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
    catch (java.security.InvalidKeyException e) {
      throw new RuntimeException(e);
    }
    catch (java.io.UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  private byte[] getSignatureKey(String key, String dateStamp, String region, String service) {
    try {
      byte[] kSecret = ("AWS4" + key).getBytes("UTF8");
      byte[] kDate    = hmacSHA256(dateStamp, kSecret);
      byte[] kRegion  = hmacSHA256(region, kDate);
      byte[] kService = hmacSHA256(service, kRegion);
      return hmacSHA256("aws4_request", kService);
    }
    catch (java.io.UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  private String hexString(byte[] data) {
    return DatatypeConverter.printHexBinary(data).toLowerCase();
  }

  private String computeSHA256(String input) {
    try {
      MessageDigest messageDigest = (MessageDigest) MESSAGE_DIGEST_SHA_256.clone();
      return hexString(messageDigest.digest(input.getBytes()));
    }
    catch (java.lang.CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
  }

  private <Y> Observable<Long> getBackoffStrategyDelay(Request<Y> request, int cnt, AmazonClientException error) {
      if (cnt == 0) return Observable.just(0L);
      else {
        long delay = clientConfiguration.getRetryPolicy().getBackoffStrategy().delayBeforeNextRetry(request.getOriginalRequest(), error, cnt);
        return Observable.timer(delay, TimeUnit.MILLISECONDS);
      }
    }

  protected <X, Y extends AmazonWebServiceRequest> Observable<X> invokeStax(
    Request<Y> request,
    Unmarshaller<X,StaxUnmarshallerContext> unmarshaller,
    List<Unmarshaller<AmazonServiceException,Node>> errorUnmarshallers,
    ExecutionContext executionContext
  ) {
    StaxRxNettyResponseHandler<X> responseHandler = new StaxRxNettyResponseHandler<X>(unmarshaller);
    XmlRxNettyErrorResponseHandler errorResponseHandler = new XmlRxNettyErrorResponseHandler(errorUnmarshallers);

    return invoke(request, responseHandler, errorResponseHandler, executionContext);
  }

  protected <X, Y extends AmazonWebServiceRequest> Observable<X> invokeJson(
    Request<Y> request,
    Unmarshaller<X,JsonUnmarshallerContext> unmarshaller,
    List<JsonErrorUnmarshaller> errorUnmarshallers,
    ExecutionContext executionContext
  ) {
    JsonRxNettyResponseHandler<X> responseHandler = new JsonRxNettyResponseHandler<X>(unmarshaller);
    JsonRxNettyErrorResponseHandler errorResponseHandler = new JsonRxNettyErrorResponseHandler(request.getServiceName(), errorUnmarshallers);

    return invoke(request, responseHandler, errorResponseHandler, executionContext);
  }

  protected <X, Y extends AmazonWebServiceRequest> Observable<X> invoke(
    Request<Y> request,
    RxNettyResponseHandler<AmazonWebServiceResponse<X>> responseHandler,
    RxNettyResponseHandler<AmazonServiceException> errorResponseHandler,
    ExecutionContext executionContext
  ) {
    final AtomicReference<AmazonClientException> error = new AtomicReference<AmazonClientException>(null);
    final AtomicInteger cnt = new AtomicInteger(0);

    return Observable.<X,String>using(
      () -> { return ""; },
      (ignore) -> {
        assert(cnt.get() == 0 || error.get() != null);
        if (cnt.get() == 0 || (cnt.get() < clientConfiguration.getRetryPolicy().getMaxErrorRetry() && clientConfiguration.getRetryPolicy().getRetryCondition().shouldRetry(request.getOriginalRequest(), error.get(), cnt.get()))) {
          return getBackoffStrategyDelay(request, cnt.get(), error.get())
          .flatMap(i -> {
            try {
              return invokeImpl(request, responseHandler, errorResponseHandler, executionContext);
            }
            catch (java.io.UnsupportedEncodingException e) {
              return Observable.<X>error(e);
            }
          })
          .doOnNext(n -> {
            error.set(null);
          })
          .onErrorResumeNext(t -> {
            error.set((AmazonClientException) t);
            return Observable.just((X) null);
          });
        }
        else return Observable.<X>error(error.get());
      },
      (ignore) -> {
        cnt.getAndIncrement();
      }
    )
    .repeat()
    .filter((v) -> {
       return v != null;
    })
    .first();
  }

  protected <X,Y extends AmazonWebServiceRequest> Observable<X> invokeImpl(
    Request<Y> request,
    RxNettyResponseHandler<AmazonWebServiceResponse<X>> responseHandler,
    RxNettyResponseHandler<AmazonServiceException> errorResponseHandler,
    ExecutionContext executionContext
  ) throws java.io.UnsupportedEncodingException {
    request.setEndpoint(endpoint);
    request.setTimeOffset(timeOffset);
    AmazonWebServiceRequest originalRequest = request.getOriginalRequest();

    for (Map.Entry<String,String> e : originalRequest.copyPrivateRequestParameters().entrySet()) {
      request.addParameter(e.getKey(), e.getValue());
    }

    AWSCredentials credentials = request.getOriginalRequest().getRequestCredentials();
    if (credentials == null) {
      credentials = awsCredentialsProvider.getCredentials();
    }

    executionContext.setCredentials(credentials);

// execute
    ProgressListener listener = originalRequest.getGeneralProgressListener();
    if (originalRequest.getCustomRequestHeaders() != null) {
      request.getHeaders().putAll(originalRequest.getCustomRequestHeaders());
    }

// new
    long startTime = System.currentTimeMillis();
    String method = "POST";
    String version = "2014-10-01";
    String service = request.getServiceName().substring(6).toLowerCase();
    if (service.endsWith("v2")) service = service.substring(0, service.length() - 2);
    String host = endpoint.getHost();
    String region = AwsHostNameUtils.parseRegionName(host, service);

    StringBuffer sb = new StringBuffer();
    for (Map.Entry<String,String> e : request.getParameters().entrySet()) {
      if (sb.length() > 0) sb.append("&");
      sb.append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
    }
    String canonicalQuerystring = sb.toString();
    String content = (request.getContent() == null) ? "" : ((StringInputStream) request.getContent()).getString();

    String accessKey = credentials.getAWSAccessKeyId();
    String secretKey = credentials.getAWSSecretKey();

    String amzDate = ISODateTimeFormat.basicDateTimeNoMillis().withZoneUTC().print(startTime);
    String datestamp  = ISODateTimeFormat.basicDate().withZoneUTC().print(startTime);

    Map<String,String> headers = new ConcurrentHashMap<String,String>();
    headers.put("Accept-encoding", "gzip");
    headers.put("Host",  host);
    headers.put("X-Amz-Date", amzDate);
    if (credentials instanceof AWSSessionCredentials)
      headers.put("x-amz-security-token", ((AWSSessionCredentials) credentials).getSessionToken());
    request.getHeaders().entrySet().stream().forEach(e -> {
      headers.put(e.getKey(), e.getValue());
    });

    String algorithm = "AWS4-HMAC-SHA256";
    String credentialScope = datestamp + "/" + region + "/" + service + "/aws4_request";
    String amzCredential = URLEncoder.encode(accessKey + "/" + credentialScope, "UTF-8");
    String canonicalHeaders = headers.entrySet().stream().sorted((e1, e2) -> {
      return e1.getKey().toLowerCase().compareTo(e2.getKey().toLowerCase());
    }).map(e -> {
      return e.getKey().toLowerCase() + ":" + e.getValue() + "\n";
    }).reduce((s1, s2) -> s1 + s2).get();
    String signedHeaders = headers.entrySet().stream().sorted((e1, e2) -> {
      return e1.getKey().toLowerCase().compareTo(e2.getKey().toLowerCase());
    }).map(e -> {
      return e.getKey().toLowerCase();
    }).reduce((s1, s2) -> s1 + ";" + s2).get();

    String canonicalUri = request.getResourcePath();
    if (canonicalUri == null || canonicalUri.length() == 0) canonicalUri = "/";
    //String canonicalQuerystring = "";

    String payloadHash = computeSHA256(content);
    String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuerystring + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

    String stringToSign = algorithm + "\n" + amzDate + "\n" + credentialScope + "\n" + computeSHA256(canonicalRequest);

    byte[] signingKey = getSignatureKey(secretKey, datestamp, region, service);
    String signature = hexString(hmacSHA256(stringToSign, signingKey));

    String authorizationHeader = algorithm + " Credential=" + accessKey + "/" + credentialScope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

    String path = canonicalUri + ((canonicalQuerystring.length() == 0) ? "" : "?" + canonicalQuerystring );

    HttpClientRequest<ByteBuf> rxRequest = HttpClientRequest.create(HttpMethod.valueOf(request.getHttpMethod().toString()), path);
    HttpRequestHeaders rHeaders = rxRequest.getHeaders();
    rHeaders.set("Authorization", authorizationHeader);
    headers.entrySet().stream().forEach(e -> {
      rHeaders.set(e.getKey(), e.getValue());
    });
    rxRequest.withContent(content);
    return getClient(host).submit(rxRequest)
    .flatMap(response -> {
      if (response.getStatus().code() / 100 == 2) {
        try {
          return responseHandler.handle(response).map(r -> { return r.getResult(); });
        }
        catch (Exception e) {
          return Observable.error(e);
        }
      }
      else {
        try {
          return errorResponseHandler.handle(response).flatMap(e -> {
            e.setServiceName(request.getServiceName());
            return Observable.error(e);
          });
        }
        catch (Exception e) {
          return Observable.error(e);
        }
      }
    })
    .onErrorResumeNext(t -> {
      if (t instanceof AmazonClientException) return Observable.error(t);
      else return  Observable.error(new AmazonClientException(t));
    });
  }

  private HttpClient<ByteBuf,ByteBuf> getClient(String host) {
    Protocol protocol = clientConfiguration.getProtocol();
    String key = protocol + "|" + host;
    if (!CLIENTS.containsKey(key)) {
      boolean isSecure;
      int port;
      if (Protocol.HTTP.equals(protocol)) {
        isSecure = false;
        port = 80;
      }
      else if (Protocol.HTTPS.equals(protocol)) {
        isSecure = true;
        port = 443;
      }
      else {
        throw new IllegalStateException("unknown protocol: " + protocol);
      }

      HttpClientConfig config = new HttpClient.HttpClientConfig.Builder()
        .setFollowRedirect(true)
        .readTimeout(clientConfiguration.getSocketTimeout(), TimeUnit.MILLISECONDS)
        .build();

      HttpClient<ByteBuf,ByteBuf> client = RxNetty.<ByteBuf,ByteBuf>newHttpClientBuilder(host, port)
        .withName(host + "." + port)
        .config(config)
        .channelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, clientConfiguration.getConnectionTimeout())
        .withMaxConnections(clientConfiguration.getMaxConnections())
        .withIdleConnectionsTimeoutMillis(clientConfiguration.getConnectionTTL())
        .enableWireLogging(LogLevel.ERROR)
        .withSslEngineFactory((isSecure) ? DefaultFactories.trustAll() : null)
        .pipelineConfigurator(
          new PipelineConfiguratorComposite<HttpClientResponse<ByteBuf>,HttpClientRequest<ByteBuf>>(
            new HttpClientPipelineConfigurator<ByteBuf,ByteBuf>(),
            new HttpDecompressionConfigurator()
          )
        )
        .build();
      CLIENTS.putIfAbsent(key, client);
    }
    return CLIENTS.get(key);
  }

  public class HttpDecompressionConfigurator implements PipelineConfigurator<ByteBuf,ByteBuf> {
    @Override
    public void configureNewPipeline(ChannelPipeline pipeline) {
      pipeline.addLast("deflater", new HttpContentDecompressor());
    }
  }
}