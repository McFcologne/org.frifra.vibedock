package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"vibedock.admin-password=audit-original-password","vibedock.polling=false","server.address=127.0.0.1","vibedock.admin-address=127.0.0.1","spring.config.import=optional:file:missing-audit-env[.properties]"})
@AutoConfigureMockMvc
class SecurityRegressionTest {
 static Path data; static int adminPort;
 @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) throws Exception {
  data=Files.createTempDirectory("vibedock-security-audit-");
  try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){adminPort=socket.getLocalPort();}
  r.add("vibedock.data-dir",data::toString);r.add("vibedock.log-dir",()->data.resolve("logs").toString());
  r.add("vibedock.admin-port",()->adminPort);
 }
 @LocalServerPort int port;
 @Autowired MockMvc mvc; @Autowired ClientRegistry registry; @Autowired DevicePoke poke; @Autowired AdminAccount admin; @Autowired ClientPairing pairing;
 final HttpClient http=HttpClient.newHttpClient();

 @Test void webDisplaySecretsAndQueryStringsAreRedactedFromAccessLogs() throws Exception {
  var view=registry.addWeb("Synthetic audit display",null,30);
  String secret=view.web().secret();
  assertEquals(200,http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/web/"+secret)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
  for(String path:List.of("/web/"+secret+"/image.png?token="+secret,"/%77eb/"+secret,"/web/invalid?token="+secret))
   http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.discarding());
  String logs="";
  for(int i=0;i<30;i++){
   try(var files=Files.list(data.resolve("logs"))){
    logs=String.join("\n",files.filter(p->p.getFileName().toString().startsWith("access")).map(p->{try{return Files.readString(p);}catch(Exception e){throw new RuntimeException(e);}}).toList());
   }
   if(logs.lines().filter(l->l.contains("/web/[redacted]")).count()>=4)break;
   Thread.sleep(100);
  }
  assertFalse(logs.contains(secret),"Reusable URL credentials must never appear in access logs");
  assertFalse(logs.contains("?token="));
  assertTrue(logs.lines().filter(l->l.contains("/web/[redacted]")).count()>=4,logs);
  registry.remove(view.identifier());
 }

 @Test void deviceReportsCannotChangeTheServerCallbackEndpoint() throws Exception {
  var received=new CountDownLatch(1);String id="vibedock-audit-device",key="b".repeat(64);
  var observer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  observer.createContext("/pull",exchange->{if(exchange.getRequestMethod().equals("POST"))received.countDown();exchange.sendResponseHeaders(202,-1);exchange.close();});observer.start();
  try{
   registry.paired(id,"Synthetic device",key,"192.0.2.123",observer.getAddress().getPort());
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/clients/"+id+"/image"))
    .header("Authorization","Bearer "+key).header("X-VibeDock-Device-IP","127.0.0.1").GET().build();
   assertEquals(200,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
   assertEquals("127.0.0.1",registry.get(id).ip(),"Reported address remains visible on the display");
   assertEquals("192.0.2.123",registry.address(id).ip());
   registry.paired(id,"Synthetic USB device",key,ClientPairing.UNKNOWN_IP,observer.getAddress().getPort());
   assertEquals(200,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
   assertEquals(ClientPairing.UNKNOWN_IP,registry.address(id).ip());
   ReflectionTestUtils.setField(poke,"enabled",true);
   poke.pullNow(List.of(registry.address(id)));
   assertFalse(received.await(300,TimeUnit.MILLISECONDS),"USB reports must not cause server callbacks");
  }finally{ReflectionTestUtils.setField(poke,"enabled",false);observer.stop(0);registry.remove(id);}
 }

 @Test void httpPairingRemainsAvailableWithAVisibleWarning() throws Exception {
  var captured=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
  var observer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  observer.createContext("/pair",exchange->{
   captured.set(new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody()));
   byte[] reply="{\"identifier\":\"vibedock-audit-pair\",\"name\":\"Synthetic pair target\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
   exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
  });observer.start();
  try{
   var invitation=pairing.invite("127.0.0.1",observer.getAddress().getPort(),"123456","Synthetic pair target",List.of("http://127.0.0.1:9"));
   assertEquals("123456",captured.get().path("code").asText());
   assertTrue(registry.authenticate(invitation.view().identifier(),captured.get().path("key").asText()));
   captured.set(null);
   mvc.perform(post("/admin/clients/invite").with(httpBasic("admin","audit-original-password")).with(csrf())
    .param("ip","127.0.0.1").param("code","123456").param("port",String.valueOf(observer.getAddress().getPort()))
    .with(r->{r.setLocalPort(adminPort);return r;})).andExpect(redirectedUrl("/admin/devices"))
    .andExpect(flash().attributeExists("clientMessage")).andExpect(flash().attributeCount(1));
   assertNotNull(captured.get());
   assertTrue(registry.authenticate(invitation.view().identifier(),captured.get().path("key").asText()));
   mvc.perform(get("/admin/devices").with(httpBasic("admin","audit-original-password")).with(r->{r.setLocalPort(adminPort);return r;}))
    .andExpect(content().string(org.hamcrest.Matchers.containsString("HTTP pairing is unencrypted.")))
    .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"invite-code\"")));
   registry.remove(invitation.view().identifier());
  }finally{observer.stop(0);}
 }

 private MockHttpSession signIn(String password) throws Exception {
  var result=mvc.perform(post("/login").param("username","admin").param("password",password).with(csrf())
   .with(r->{r.setLocalPort(adminPort);return r;})).andExpect(redirectedUrl("/admin")).andReturn();
  var session=(MockHttpSession)result.getRequest().getSession(false);assertNotNull(session);return session;
 }
 private void rejected(MockHttpSession session) throws Exception {
  mvc.perform(get("/admin/designs").session(session).with(r->{r.setLocalPort(adminPort);return r;})).andExpect(redirectedUrl("/login?expired"));
  assertTrue(session.isInvalid());
 }
 @Test void passwordChangesAndResetsRevokeEveryExistingSession() throws Exception {
  var first=signIn("audit-original-password");var second=signIn("audit-original-password");
  try{
   mvc.perform(post("/admin/password").session(first).with(csrf()).param("current","wrong")
    .param("password","audit-new-password").param("repeat","audit-new-password").with(r->{r.setLocalPort(adminPort);return r;}))
    .andExpect(flash().attributeExists("passwordError"));
   mvc.perform(get("/admin/designs").session(second).with(r->{r.setLocalPort(adminPort);return r;})).andExpect(status().isOk());
   mvc.perform(post("/admin/password").session(first).with(csrf()).param("current","audit-original-password")
    .param("password","audit-new-password").param("repeat","audit-new-password").with(r->{r.setLocalPort(adminPort);return r;}))
    .andExpect(redirectedUrl("/login?expired"));
   rejected(first);rejected(second);
   mvc.perform(get("/admin/designs").with(httpBasic("admin","audit-original-password")).with(r->{r.setLocalPort(adminPort);return r;})).andExpect(status().isUnauthorized());
   var third=signIn("audit-new-password");var fourth=signIn("audit-new-password");
   mvc.perform(post("/admin/password").session(third).with(csrf()).param("current","audit-new-password").param("reset","1")
    .with(r->{r.setLocalPort(adminPort);return r;})).andExpect(redirectedUrl("/login?expired"));
   rejected(third);rejected(fourth);signIn("audit-original-password");
  }finally{if(admin.overridden())admin.reset("audit-new-password");}
 }

 @Test void administrationMutationsStillRequireAuthenticationAndCsrf() throws Exception {
  for(String endpoint:List.of("/admin/settings","/admin/sources/add","/admin/firmware/setup","/admin/designs/default")){
   mvc.perform(post(endpoint).with(r->{r.setLocalPort(adminPort);return r;})).andExpect(status().isForbidden());
   mvc.perform(post(endpoint).with(httpBasic("admin","audit-original-password")).with(r->{r.setLocalPort(adminPort);return r;})).andExpect(status().isForbidden());
  }
  mvc.perform(get("/admin/designs").with(r->{r.setLocalPort(adminPort);return r;})).andExpect(status().isUnauthorized());
  mvc.perform(get("/admin/designs").with(httpBasic("admin","audit-original-password")).with(r->{r.setLocalPort(port);return r;})).andExpect(status().isNotFound());
 }
}
