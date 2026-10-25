package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManagerFactory;
import life.work.IntFit.backend.controller.TeamMemberController;
import life.work.IntFit.backend.controller.WhatsAppTestSendController;
import life.work.IntFit.backend.controller.WhatsAppWebhookController;
import life.work.IntFit.backend.dto.TeamMemberDTO;
import life.work.IntFit.backend.mapper.TeamMemberMapper;
import life.work.IntFit.backend.model.entity.TeamMember;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.Worksite;
import life.work.IntFit.backend.model.entity.WorksiteContact;
import life.work.IntFit.backend.model.entity.Contact;
import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import life.work.IntFit.backend.mapper.MasterWorksiteMapper;
import life.work.IntFit.backend.repository.*;
import life.work.IntFit.backend.service.MasterWorksiteService;
import org.mapstruct.factory.Mappers;
import life.work.IntFit.backend.repository.TeamMemberRepository;
import life.work.IntFit.backend.service.TeamMemberService;
import life.work.IntFit.backend.service.WhatsAppTestSendService;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static life.work.IntFit.backend.reporting.ReportingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MySQL + JPA transaction manager, no Spring application/context, startup hooks or real HTTP.
 * Only scripts/test-reporting-mysql.ps1 can supply a fresh matching container/database guard.
 * Default Maven runs skip this class before any connection or destructive fixture setup. */
@EnabledIfEnvironmentVariable(named="INTFITOUT_MYSQL_TEST_ENABLED", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportingMySqlIntegrationTest {
    private static final String DATABASE="intfitout_reporting_validation";
    private static final String ADMIN="X-WhatsApp-Reporting-Admin-Secret";
    private static final String BASE="/api/whatsapp/reporting/admin";
    private static final String SIGNING="synthetic-db-webhook-signing-only";
    private static final LocalDate DAY=LocalDate.of(2026,10,22);
    private static final List<String> FINANCIAL=List.of("work_assignments","work_assignment_overtime","payroll_week","payroll_line","payroll_adjustment");
    private final ObjectMapper mapper=new ObjectMapper();
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean factory;
    private EntityManagerFactory emf;
    private JpaTransactionManager manager;
    private FaultStore store;
    private ReportingService service;
    private Map<String,String> env;
    private ReportingServiceTest.MutableClock clock;
    private final AtomicInteger sends=new AtomicInteger();
    private volatile ReportingSender transport;
    private Map<String,List<Map<String,Object>>> financialSnapshot;

    @BeforeAll void connectOnlyToFreshGuardedContainer() throws Exception {
        String port=System.getenv("INTFITOUT_MYSQL_TEST_PORT");
        String user=System.getenv("INTFITOUT_MYSQL_TEST_USER");
        String password=System.getenv("INTFITOUT_MYSQL_TEST_PASSWORD");
        String runId=System.getenv("INTFITOUT_MYSQL_TEST_RUN_ID");
        assertTrue(port!=null && port.matches("[0-9]{1,5}"),"Use the disposable-container runner; no default datasource is allowed");
        assertTrue(user!=null && user.matches("rv_[a-f0-9]{12}"));
        assertTrue(password!=null && password.matches("[a-f0-9]{64}"));
        assertTrue(runId!=null && runId.matches("[a-f0-9]{32}"));
        int p=Integer.parseInt(port);assertTrue(p>1023 && p<=65535);
        dataSource=new DriverManagerDataSource();dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://127.0.0.1:"+p+"/"+DATABASE+
                "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=10000");
        dataSource.setUsername(user);dataSource.setPassword(password);
        jdbc=new JdbcTemplate(dataSource);jdbc.setQueryTimeout(10);
        assertEquals(DATABASE,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(List.of(runId),jdbc.queryForList("SELECT run_id FROM intfitout_validation_guard",String.class),"Refusing fixture DDL without this fresh run's guard");
        String version=jdbc.queryForObject("SELECT VERSION()",String.class);
        assertEquals("9.7.2",version,"The disposable server must match the intended MySQL validation version");
        String isolation=jdbc.queryForObject("SELECT @@transaction_isolation",String.class);
        try(Connection c=dataSource.getConnection()) {
            System.out.println("reporting_mysql_version="+version+" isolation="+isolation+" driver="+c.getMetaData().getDriverVersion()+
                    " transaction_manager=JpaTransactionManager application_started=false meta=mocked");
        }
        bootstrapOrm();
    }
    private void bootstrapOrm() {
        // One actual legacy entity, explicit datasource, schema generation off. No component scanning.
        factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(dataSource);
        factory.setPersistenceUnitName("disposable-reporting-validation");
        factory.setManagedTypes(PersistenceManagedTypes.of(TeamMember.class.getName(),MasterWorksite.class.getName(),Worksite.class.getName(),WorksiteContact.class.getName(),Contact.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none","hibernate.show_sql","false",
                "hibernate.jdbc.time_zone","UTC","hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet();emf=Objects.requireNonNull(factory.getObject());
        manager=new JpaTransactionManager(emf);manager.setDataSource(dataSource);manager.afterPropertiesSet();
    }
    @AfterAll void closeOrm() { if(factory!=null)factory.destroy(); }
    @BeforeEach void representativeSchemaOnlyInGuardedDatabase() throws Exception {
        try(Connection c=dataSource.getConnection()) { ScriptUtils.executeSqlScript(c,new ClassPathResource("reporting/mysql-legacy-baseline.sql")); }
        financialSnapshot=financialState();
        env=ReportingServiceTest.environment();clock=new ReportingServiceTest.MutableClock();sends.set(0);
        transport=payload -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Meta must be outside the database transaction");
            assertEquals(env.get("WHATSAPP_REPORTING_PILOT_RECIPIENT"),mapperRead(payload).path("to").asText());
            return new ReportingSender.Result("ACCEPTED","wamid.mysqlMock"+sends.incrementAndGet());
        };
        recreateServices();
    }
    @AfterEach void financialRecordsNeverChange() { if(financialSnapshot!=null)assertEquals(financialSnapshot,financialState()); }
    private Map<String,List<Map<String,Object>>> financialState() {
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for(String table:FINANCIAL)result.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY id"));
        return result;
    }
    private void migrate() throws Exception {
        try(Connection c=dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/sql/001-whatsapp-reporting.sql"));
            ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/sql/002-whatsapp-reporting-other-and-nicknames.sql"));
        }
    }
    private void configured() throws Exception {
        migrate();service.configureWorker(7,true,"+970599000007");service.configureWorker(8,true,"+972599000008");
        service.configureSite(11,true);service.configureSite(12,true);
    }
    private void recreateServices() {
        store=new FaultStore();service=new ReportingService(store,new ReportingSettings(env::get),payload -> transport.send(payload),clock,mapper);
    }
    private String initiate() { return service.initiate(DAY).id(); }
    private Outbound current(String id) { return store.outboundFor(id).getLast(); }
    private void dispatch(String request) { service.dispatch(current(request).id()); }
    private String token(String request,String verb,long target) {
        List<String> tokens=new ArrayList<>();ReportingServiceTest.collect(mapperRead(current(request).payload()),tokens);
        return tokens.stream().filter(t -> {var a=ReportingFlow.action(t);return a!=null && a.verb().equals(verb) && a.id()==target;}).findFirst().orElseThrow();
    }
    private JsonNode mapperRead(String json) {try{return mapper.readTree(json);}catch(Exception e){throw new AssertionError(e);}}
    private JsonNode value(String messageId,String action,String context,String type) {
        ObjectNode v=mapper.createObjectNode();v.putObject("metadata").put("phone_number_id","123400007");
        ObjectNode m=v.putArray("messages").addObject();m.put("id",messageId).put("from","970599000007").put("timestamp",Long.toString(clock.instant().getEpochSecond()));
        m.putObject("context").put("id",context);
        if(type.equals("button")){m.put("type","button");m.putObject("button").put("payload",action);}
        else {m.put("type","interactive");m.putObject("interactive").put("type",type).putObject(type).put("id",action).put("title","synthetic title ignored");}
        return v;
    }
    private void act(String id,String verb,long target) {
        dispatch(id);Outbound o=current(id);
        String type=o.kind().equals("TEMPLATE") ? "button" : mapperRead(o.payload()).path("interactive").path("type").asText().equals("list") ? "list_reply" : "button_reply";
        service.acceptVerifiedValue(value("wamid.mysqlIn"+UUID.randomUUID(),token(id,verb,target),o.metaId(),type));
    }
    private int count(String table) { return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)); }
    private byte[] signedBody(JsonNode value) throws Exception {
        return mapper.writeValueAsBytes(Map.of("object","whatsapp_business_account","entry",List.of(Map.of("changes",List.of(Map.of("field","messages","value",value))))));
    }
    private String signature(byte[] body) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SIGNING.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(mac.doFinal(body));
    }
    private WhatsAppWebhookController webhook() throws Exception {
        var constructor=WhatsAppWebhookController.class.getDeclaredConstructor(ObjectMapper.class,String.class,String.class,ReportingService.class);
        constructor.setAccessible(true);return constructor.newInstance(mapper,"synthetic-verify",SIGNING,service);
    }
    private MockMvc webhookMvc() throws Exception { return MockMvcBuilders.standaloneSetup(webhook()).build(); }
    private MockMvc adminMvc() { return MockMvcBuilders.standaloneSetup(new ReportingAdminController(new ReportingSettings(env::get),service)).build(); }
    private MasterWorksiteService masterService() {
        MasterWorksiteRepository repo=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(MasterWorksiteRepository.class);
        CityRepository cities=org.mockito.Mockito.mock(CityRepository.class);
        var city=new life.work.IntFit.backend.model.entity.City();city.setName("fixture city");
        org.mockito.Mockito.when(cities.findByNameIgnoreCase("fixture city")).thenReturn(Optional.of(city));
        return new MasterWorksiteService(repo,org.mockito.Mockito.mock(WorksiteRepository.class),Mappers.getMapper(MasterWorksiteMapper.class),cities,org.mockito.Mockito.mock(AreaRepository.class));
    }
    private ReportingResolutionService resolutions() {return new ReportingResolutionService(store,masterService(),clock,mapper);}
    private JsonNode text(String mid,String description,String context) {
        ObjectNode v=(ObjectNode)value(mid,"",context,"button");ObjectNode m=(ObjectNode)v.path("messages").get(0);
        m.put("type","text");m.remove("button");if(context==null)m.remove("context");m.putObject("text").put("body",description);return v;
    }
    private String capture() {String id=initiate();act(id,"start",0);act(id,"other",0);dispatch(id);return id;}
    private void description(String id,String description) {service.acceptVerifiedValue(text("wamid.mysqlText"+UUID.randomUUID(),description,current(id).metaId()));}
    private String submittedOther() throws Exception {configured();String id=capture();description(id,"قرب المدرسة القديمة");act(id,"cow",8);act(id,"done",0);act(id,"finish",0);act(id,"submit",0);return id;}
    private List<Throwable> race(List<Runnable> actions) throws Exception {
        CountDownLatch ready=new CountDownLatch(actions.size()),go=new CountDownLatch(1);
        try(ExecutorService pool=Executors.newFixedThreadPool(actions.size())) {
            List<Future<Throwable>> futures=new ArrayList<>();
            for(Runnable action:actions)futures.add(pool.submit(() -> {
                ready.countDown();if(!go.await(10,TimeUnit.SECONDS))throw new AssertionError("Race barrier timeout");
                try{action.run();return null;}catch(RuntimeException e){return e;}
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();
            List<Throwable> errors=new ArrayList<>();for(var f:futures){Throwable error=f.get(25,TimeUnit.SECONDS);if(error!=null)errors.add(error);}return errors;
        }
    }
    private void exactlyOneDuplicate(List<Runnable> actions) throws Exception {
        List<Throwable> errors=race(actions);assertEquals(1,errors.size());assertInstanceOf(DuplicateKeyException.class,errors.getFirst());
    }

    @Test void actualMigrationPreservesExistingRecordsAndLeavesEverythingOptIn() throws Exception {
        var workers=jdbc.queryForList("SELECT id,name,role,experience,contact,daily_wage FROM team_members ORDER BY id");
        var sites=jdbc.queryForList("SELECT id,approved_name,notes,city FROM master_worksites ORDER BY id");
        migrate();
        assertEquals(workers,jdbc.queryForList("SELECT id,name,role,experience,contact,daily_wage FROM team_members ORDER BY id"));
        assertEquals(sites,jdbc.queryForList("SELECT id,approved_name,notes,city FROM master_worksites ORDER BY id"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE whatsapp_reporting_eligible OR whatsapp_number IS NOT NULL",Integer.class));
        assertTrue(store.selectableSites().isEmpty());assertEquals(0,count("whatsapp_report_requests"));
        assertThrows(ReportingProblem.class,this::initiate);assertEquals(0,sends.get());
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'whatsapp_report_%'",Integer.class));
    }
    @Test void existingJpaWorkerUpdatesPreserveTheNewExplicitSetupFields() throws Exception {
        configured();TeamMemberRepository repo=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(TeamMemberRepository.class);
        TeamMemberService legacy=new TeamMemberService(repo,TeamMemberMapper.INSTANCE,null);
        TeamMemberDTO dto=new TeamMemberDTO();dto.setContact("Updated synthetic free-text contact; not a WhatsApp opt-in");
        store.transaction(() -> legacy.updateTeamMember(7L,dto));
        assertEquals(dto.getContact(),jdbc.queryForObject("SELECT contact FROM team_members WHERE id=7",String.class));
        assertEquals("970599000007",store.transaction(() -> store.lockWorker(7)).number());assertTrue(store.transaction(() -> store.lockWorker(7)).eligible());
    }
    @Test void concurrentInitiationReturnsOneWorkerDateRequestAndOneIntent() throws Exception {
        configured();Set<String> ids=ConcurrentHashMap.newKeySet();List<Runnable> jobs=new ArrayList<>();
        for(int i=0;i<6;i++)jobs.add(() -> ids.add(service.initiate(DAY).id()));
        assertTrue(race(jobs).isEmpty());assertEquals(1,ids.size());assertEquals(1,count("whatsapp_report_requests"));assertEquals(1,count("whatsapp_report_outbound"));assertEquals(0,sends.get());
    }
    @Test void databaseWorkerDateUniqueConstraintRejectsConcurrentInsertsWithoutServiceLocks() throws Exception {
        configured();List<Runnable> jobs=new ArrayList<>();
        for(int i=0;i<2;i++)jobs.add(() -> store.transaction(() -> {
            store.insertRequest(new Request(UUID.randomUUID().toString(),7,"970599000007","123400007",DAY,"START",0,null,null,clock.instant(),null,null));return null;
        }));
        exactlyOneDuplicate(jobs);assertEquals(1,count("whatsapp_report_requests"));
    }
    @Test void concurrentNumberSetupHasOneWinnerAndPrefixesRemainDistinct() throws Exception {
        migrate();exactlyOneDuplicate(List.of(() -> service.configureWorker(7,true,"+970599000007"),() -> service.configureWorker(8,true,"970599000007")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE whatsapp_number='970599000007'",Integer.class));
        service.configureWorker(9,true,"+972599000007");assertEquals("972599000007",store.transaction(() -> store.lockWorker(9)).number());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE whatsapp_reporting_eligible",Integer.class));
    }
    @Test void mysqlEnforcesPhoneFormatAndExplicitEligibilityChecks() throws Exception {
        migrate();
        assertCheckViolation(() -> jdbc.update("UPDATE team_members SET whatsapp_reporting_eligible=TRUE WHERE id=7"));
        for(String invalid:List.of("+970599000007","0599000007","970 599000007"))
            assertCheckViolation(() -> jdbc.update("UPDATE team_members SET whatsapp_number=? WHERE id=7",invalid));
        assertFalse(store.transaction(() -> store.lockWorker(7)).eligible());assertNull(store.transaction(() -> store.lockWorker(7)).number());
    }
    private static void assertCheckViolation(Runnable action) {
        // Connector/J reports CHECK failures as HY000/3819; Spring may leave them uncategorized.
        DataAccessException error=assertThrows(DataAccessException.class,action::run);
        java.sql.SQLException sql=assertInstanceOf(java.sql.SQLException.class,error.getMostSpecificCause());
        assertEquals(3819,sql.getErrorCode());
    }
    @Test void inboundEventKeyAndOutgoingRevisionAreDatabaseUniqueUnderConcurrency() throws Exception {
        configured();String id=initiate();
        Inbox inbox=new Inbox("a".repeat(64),"wamid.unique",null,"MESSAGE",null,id,"IGNORED",clock.instant(),clock.instant());
        exactlyOneDuplicate(List.of(() -> store.transaction(() -> {store.insertInbox(inbox);return null;}),() -> store.transaction(() -> {store.insertInbox(inbox);return null;})));
        List<Runnable> jobs=new ArrayList<>();
        for(int i=0;i<2;i++)jobs.add(() -> store.transaction(() -> {store.insertOutbound(new Outbound(UUID.randomUUID().toString(),id,1,"PROMPT","{}","PENDING",null,null,clock.instant()));return null;}));
        exactlyOneDuplicate(jobs);assertEquals(1,count("whatsapp_report_inbox"));assertEquals(2,count("whatsapp_report_outbound"));
    }
    @Test void siteMentionAndMetaIdUniqueConstraintsAreReal() throws Exception {
        configured();String id=initiate();
        store.transaction(() -> {store.addSite(new Site(id,11,"fixture label"));store.addMention(new Coworker(id,11,8,"fixture coworker"));return null;});
        assertThrows(DuplicateKeyException.class,() -> store.transaction(() -> {store.addSite(new Site(id,11,"duplicate"));return null;}));
        assertThrows(DuplicateKeyException.class,() -> store.transaction(() -> {store.addMention(new Coworker(id,11,8,"duplicate"));return null;}));
        dispatch(id);String meta=current(id).metaId();
        store.transaction(() -> {store.insertOutbound(new Outbound(UUID.randomUUID().toString(),id,1,"PROMPT","{}","PENDING",null,null,clock.instant()));return null;});
        Outbound other=current(id);
        assertThrows(DuplicateKeyException.class,() -> store.transaction(() -> {store.saveOutbound(other.result("ACCEPTED",meta,null));return null;}));
    }
    @Test void failedInboundTransactionWithholdsAckAndRollsBackEverythingThenRedeliveryCommits() throws Exception {
        configured();String id=initiate();dispatch(id);
        byte[] body=signedBody(value("wamid.rollbackReal",token(id,"start",0),current(id).metaId(),"button"));MockMvc mvc=webhookMvc();
        store.failAt.set("INBOX");
        mvc.perform(post("/webhooks/whatsapp").contentType("application/json").header("X-Hub-Signature-256",signature(body)).content(body)).andExpect(status().isServiceUnavailable());
        assertEquals("START",service.report(id).step());assertEquals(0,count("whatsapp_report_inbox"));assertEquals(1,count("whatsapp_report_outbound"));
        mvc.perform(post("/webhooks/whatsapp").contentType("application/json").header("X-Hub-Signature-256",signature(body)).content(body)).andExpect(status().isOk());
        assertEquals("SITES",service.report(id).step());assertEquals(1,count("whatsapp_report_inbox"));assertEquals(2,count("whatsapp_report_outbound"));
        mvc.perform(post("/webhooks/whatsapp").contentType("application/json").header("X-Hub-Signature-256",signature(body)).content(body)).andExpect(status().isOk());
        assertEquals(1,store.transaction(() -> store.request(id)).revision());assertEquals(2,count("whatsapp_report_outbound"));
    }
    @Test void failedSiteSelectionRollsBackSelectedSiteAndPromptTogether() throws Exception {
        configured();String id=initiate();act(id,"start",0);dispatch(id);
        JsonNode event=value("wamid.siteRollback",token(id,"site",11),current(id).metaId(),"list_reply");store.failAt.set("INBOX");
        assertThrows(DataAccessException.class,() -> service.acceptVerifiedValue(event));
        assertTrue(service.report(id).sites().isEmpty());assertEquals("SITES",service.report(id).step());assertEquals(2,count("whatsapp_report_outbound"));
        service.acceptVerifiedValue(event);assertEquals(1,service.report(id).sites().size());assertEquals("COWORKERS",service.report(id).step());assertEquals(3,count("whatsapp_report_outbound"));
    }
    @ParameterizedTest @ValueSource(booleans={true,false}) void concurrentTemplateRepliesAdvanceOnlyOnce(boolean sameMessageId) throws Exception {
        configured();String id=initiate();dispatch(id);String action=token(id,"start",0),context=current(id).metaId();List<Runnable> jobs=new ArrayList<>();
        for(int i=0;i<6;i++){JsonNode event=value("wamid.concurrentTemplate"+(sameMessageId ? "same" : i),action,context,"button");jobs.add(() -> service.acceptVerifiedValue(event));}
        assertTrue(race(jobs).isEmpty());assertEquals(1,store.transaction(() -> store.request(id)).revision());assertEquals(2,count("whatsapp_report_outbound"));assertEquals(sameMessageId ? 1 : 6,count("whatsapp_report_inbox"));
    }
    @ParameterizedTest @ValueSource(strings={"list_reply","button_reply"}) void concurrentListOrButtonActionsCannotSelectTheSameSiteTwice(String type) throws Exception {
        configured();String id=initiate();act(id,"start",0);dispatch(id);String action=token(id,"site",11),context=current(id).metaId();List<Runnable> jobs=new ArrayList<>();
        for(int i=0;i<6;i++){JsonNode event=value("wamid.concurrentSite"+i,action,context,type);jobs.add(() -> service.acceptVerifiedValue(event));}
        assertTrue(race(jobs).isEmpty());assertEquals(1,count("whatsapp_report_sites"));assertEquals(2,store.transaction(() -> store.request(id)).revision());assertEquals(3,count("whatsapp_report_outbound"));
    }
    @Test void concurrentCoworkerTapsToggleOnceAndDoNotCreateCoworkerRequests() throws Exception {
        configured();String id=initiate();act(id,"start",0);act(id,"site",11);dispatch(id);String action=token(id,"cow",8),context=current(id).metaId();List<Runnable> jobs=new ArrayList<>();
        for(int i=0;i<6;i++){JsonNode event=value("wamid.concurrentCow"+i,action,context,"list_reply");jobs.add(() -> service.acceptVerifiedValue(event));}
        assertTrue(race(jobs).isEmpty());assertEquals(1,count("whatsapp_report_coworkers"));assertEquals(1,count("whatsapp_report_requests"));assertEquals(3,store.transaction(() -> store.request(id)).revision());
    }
    @Test void recreatedOrmAndServicesResolveOldReplyToOriginalDateDespiteNewRequest() throws Exception {
        configured();String first=initiate();dispatch(first);String action=token(first,"start",0),context=current(first).metaId();
        factory.destroy();bootstrapOrm();recreateServices();clock.now=clock.now.plus(Duration.ofDays(1));String second=service.initiate(DAY.plusDays(1)).id();
        service.acceptVerifiedValue(value("wamid.afterRestart",action,context,"button"));
        assertEquals(DAY,service.report(first).workDate());assertEquals("SITES",service.report(first).step());assertEquals("START",service.report(second).step());
        assertEquals(0,store.transaction(() -> store.request(second)).revision());
    }
    @Test void submittedMultiSiteReportIsImmutableAndFinancialSnapshotsRemainUnchanged() throws Exception {
        configured();String id=initiate();act(id,"start",0);act(id,"site",11);act(id,"cow",8);act(id,"done",0);act(id,"site",12);act(id,"done",0);act(id,"finish",0);
        clock.now=Instant.parse("2026-10-23T00:30:00Z");dispatch(id);String submit=token(id,"submit",0),context=current(id).metaId();
        service.acceptVerifiedValue(value("wamid.submitReal",submit,context,"button_reply"));
        Request submitted=store.transaction(() -> store.request(id));List<Site> sites=store.sites(id);List<Coworker> mentions=store.mentions(id);
        assertEquals(DAY,submitted.workDate());assertEquals("SUBMITTED",submitted.step());assertEquals(2,sites.size());assertEquals(1,mentions.size());
        recreateServices();service.acceptVerifiedValue(value("wamid.staleSubmit",submit,context,"button_reply"));
        assertEquals(submitted,store.transaction(() -> store.request(id)));assertEquals(sites,store.sites(id));assertEquals(mentions,store.mentions(id));assertEquals("AWAITING_REVIEW",service.report(id).reviewStatus());
        adminMvc().perform(put(BASE+"/requests/"+id).header(ADMIN,env.get("WHATSAPP_REPORTING_ADMIN_SECRET")).contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());
        dispatch(id);assertEquals("ACCEPTED",current(id).state());
    }
    @Test void didNotWorkAlsoRequiresConfirmationAndPersistsWithoutSites() throws Exception {
        configured();String id=initiate();act(id,"none",0);assertEquals("DRAFT",service.report(id).reviewStatus());act(id,"submit",0);
        assertEquals("DID_NOT_WORK",service.report(id).outcome());assertEquals("AWAITING_REVIEW",service.report(id).reviewStatus());assertEquals(0,count("whatsapp_report_sites"));assertEquals(0,count("whatsapp_report_coworkers"));
    }
    @Test void failedClaimTransactionDoesNotSendAndLeavesSafePendingIntent() throws Exception {
        configured();String id=initiate();store.failAt.set("SENDING");assertThrows(DataAccessException.class,() -> dispatch(id));
        assertEquals("PENDING",current(id).state());assertEquals(0,sends.get());dispatch(id);assertEquals(1,sends.get());
    }
    @Test void mysqlConnectionInterruptionAfterAcceptanceNeverCausesAutomaticResend() throws Exception {
        configured();String id=initiate();String action=token(id,"start",0);store.abortAccepted.set(true);
        assertThrows(RuntimeException.class,() -> dispatch(id));assertEquals(1,sends.get());assertEquals("SENDING",current(id).state());
        recreateServices();service.drainOne();dispatch(id);assertEquals(1,sends.get());
        service.acceptVerifiedValue(value("wamid.uncertainReply",action,"wamid.mysqlMock1","button"));assertEquals("DEFERRED",service.report(id).inbox().getFirst().disposition());
        env.put("WHATSAPP_REPORTING_SEND_ENABLED","false");recreateServices();String original=current(id).id();service.resolve(original,"accepted","wamid.mysqlMock1");
        assertEquals("SITES",service.report(id).step());assertEquals("PENDING",current(id).state());assertEquals("APPLIED",service.report(id).inbox().getFirst().disposition());service.drainOne();assertEquals(1,sends.get());
    }
    @Test void timeoutOutcomeIsNotRetriedAfterServiceRecreationAndCanBeAbandoned() throws Exception {
        configured();String id=initiate();transport=payload -> {sends.incrementAndGet();return new ReportingSender.Result("UNCERTAIN",null);};
        dispatch(id);recreateServices();dispatch(id);new ReportingOutboxPump(new ReportingSettings(env::get),service).runOnce();assertEquals(1,sends.get());
        service.resolve(current(id).id(),"abandon",null);assertEquals("CANCELLED",current(id).state());service.drainOne();assertEquals(1,sends.get());
    }
    @Test void concurrentDispatchesObserveCommittedClaimBeforeMockHttpAndOnlyOneSends() throws Exception {
        configured();String id=initiate();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        transport=payload -> {assertFalse(TransactionSynchronizationManager.isActualTransactionActive());sends.incrementAndGet();entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return new ReportingSender.Result("ACCEPTED","wamid.concurrentDispatch");};
        try(ExecutorService pool=Executors.newSingleThreadExecutor()) {
            Future<?> first=pool.submit(() -> dispatch(id));assertTrue(entered.await(10,TimeUnit.SECONDS));
            try { assertEquals("SENDING",current(id).state());dispatch(id);assertEquals(1,sends.get()); } finally { release.countDown(); }
            first.get(15,TimeUnit.SECONDS);
        }
        assertEquals("ACCEPTED",current(id).state());
    }
    @Test void earlyReplyIsDurablyDeferredUntilAcceptanceContextIsKnown() throws Exception {
        configured();String id=initiate();String action=token(id,"start",0);
        transport=payload -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());sends.incrementAndGet();service.acceptVerifiedValue(value("wamid.beforeAcceptance",action,"wamid.earlyDb","button"));
            assertEquals("DEFERRED",service.report(id).inbox().getFirst().disposition());assertEquals("START",service.report(id).step());return new ReportingSender.Result("ACCEPTED","wamid.earlyDb");
        };
        dispatch(id);assertEquals("SITES",service.report(id).step());assertEquals("APPLIED",service.report(id).inbox().getFirst().disposition());assertEquals(2,count("whatsapp_report_outbound"));
    }
    @Test void statusesCanPrecedeAcceptanceAreDeduplicatedAndDoNotRegressRead() throws Exception {
        configured();String id=initiate();JsonNode status=mapperRead("{\"metadata\":{\"phone_number_id\":\"123400007\"},\"statuses\":[{\"id\":\"wamid.mysqlMock1\",\"recipient_id\":\"970599000007\",\"status\":\"read\",\"timestamp\":\"1792680000\"},{\"id\":\"wamid.mysqlMock1\",\"recipient_id\":\"970599000007\",\"status\":\"sent\",\"timestamp\":\"1792680001\"}]}");
        service.acceptVerifiedValue(status);dispatch(id);service.acceptVerifiedValue(status);assertEquals("READ",current(id).delivery());assertEquals(2,count("whatsapp_report_inbox"));
    }
    @Test void invalidSignaturesCannotReachDatabaseAndWrongBusinessSenderContextOrActionCannotAdvance() throws Exception {
        configured();String id=initiate();dispatch(id);String action=token(id,"start",0),context=current(id).metaId();
        byte[] body=signedBody(value("wamid.invalidSignature",action,context,"button"));int before=store.transactions.get();
        webhookMvc().perform(post("/webhooks/whatsapp").contentType("application/json").header("X-Hub-Signature-256","sha256="+"0".repeat(64)).content(body)).andExpect(status().isForbidden());assertEquals(before,store.transactions.get());
        ObjectNode wrongBusiness=(ObjectNode)value("wamid.wrongBusiness",action,context,"button");((ObjectNode)wrongBusiness.path("metadata")).put("phone_number_id","999900009");service.acceptVerifiedValue(wrongBusiness);
        ObjectNode wrongSender=(ObjectNode)value("wamid.wrongSender",action,context,"button");((ObjectNode)wrongSender.path("messages").get(0)).put("from","972599000007");service.acceptVerifiedValue(wrongSender);
        assertEquals(0,count("whatsapp_report_inbox"));
        service.acceptVerifiedValue(value("wamid.wrongContext",action,"wamid.wrong","button"));service.acceptVerifiedValue(value("wamid.forgedAction",ReportingFlow.token(store.transaction(() -> store.request(id)),"site",13,0),context,"list_reply"));
        assertEquals("START",service.report(id).step());assertEquals(1,count("whatsapp_report_outbound"));
    }
    @Test void everyAdministrationOperationRejectsMissingWrongDuplicateAndQueryCredentialsEvenWithoutReportingTables() throws Exception {
        MockMvc mvc=adminMvc();String uuid="00000000-0000-0000-0000-000000000001";
        List<Supplier<MockHttpServletRequestBuilder>> routes=List.of(
                () -> get(BASE+"/status"),() -> put(BASE+"/workers/7").contentType("application/json").content("{\"eligible\":true,\"whatsappNumber\":\"970599000007\"}"),
                () -> put(BASE+"/worksites/11").contentType("application/json").content("{\"selectable\":true}"),
                () -> post(BASE+"/requests").contentType("application/json").content("{\"workDate\":\"2026-10-22\"}"),
                () -> get(BASE+"/requests"),() -> get(BASE+"/requests/"+uuid),() -> post(BASE+"/outbound/"+uuid+"/dispatch"),
                () -> post(BASE+"/outbound/"+uuid+"/resolve").contentType("application/json").content("{\"decision\":\"abandon\"}"));
        for(var route:routes){mvc.perform(route.get()).andExpect(status().isForbidden());mvc.perform(route.get().header(ADMIN,"wrong")).andExpect(status().isForbidden());mvc.perform(route.get().header(ADMIN,env.get("WHATSAPP_REPORTING_ADMIN_SECRET"),"duplicate")).andExpect(status().isForbidden());}
        mvc.perform(get(BASE+"/status").queryParam(ADMIN,env.get("WHATSAPP_REPORTING_ADMIN_SECRET"))).andExpect(status().isForbidden());assertEquals(0,store.transactions.get());assertEquals(0,sends.get());
    }
    @Test void pilotGatesAndRequestOverridesCannotSendToOtherWorkersOrBusinessNumbers() throws Exception {
        configured();MockMvc mvc=adminMvc();
        mvc.perform(post(BASE+"/requests").header(ADMIN,env.get("WHATSAPP_REPORTING_ADMIN_SECRET")).contentType("application/json").content("{\"workDate\":\"2026-10-22\",\"recipient\":\"972599000008\"}")).andExpect(status().isBadRequest());assertEquals(0,count("whatsapp_report_requests"));
        String id=initiate();service.configureWorker(7,true,"972599000007");assertThrows(ReportingProblem.class,() -> dispatch(id));assertEquals(0,sends.get());
        service.configureWorker(7,true,"970599000007");env.put("WHATSAPP_REPORTING_PHONE_NUMBER_ID","999900009");recreateServices();assertThrows(ReportingProblem.class,() -> dispatch(id));assertEquals(0,sends.get());
        env.remove("WHATSAPP_REPORTING_PHONE_NUMBER_ID");recreateServices();assertThrows(ReportingProblem.class,() -> dispatch(id));assertEquals(0,sends.get());
    }
    @Test void disabledReportingWithAbsentTablesPreservesRealLegacyReadsAndMockedConnectivity() throws Exception {
        env.put("WHATSAPP_REPORTING_ENABLED","false");env.put("WHATSAPP_REPORTING_SEND_ENABLED","false");recreateServices();
        TeamMemberRepository repo=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(TeamMemberRepository.class);
        TeamMemberService legacy=new TeamMemberService(repo,TeamMemberMapper.INSTANCE,null);
        var controller=new TeamMemberController(legacy,null,null);
        MetaReportingSenderTest.StubHttp http=new MetaReportingSenderTest.StubHttp();
        Map<String,String> sendEnv=new HashMap<>(env);sendEnv.put("WHATSAPP_TEST_SEND_ENABLED","true");sendEnv.put("WHATSAPP_TEST_RECIPIENT","970599000007");sendEnv.put("WHATSAPP_TEST_SEND_SECRET","synthetic-test-send-secret-ONLY");
        var ctor=WhatsAppTestSendService.class.getDeclaredConstructor(ObjectMapper.class,CloseableHttpClient.class,Function.class);ctor.setAccessible(true);
        var testSender=ctor.newInstance(mapper,http,(Function<String,String>)sendEnv::get);
        try {
            MockMvc mvc=MockMvcBuilders.standaloneSetup(controller,webhook(),new WhatsAppTestSendController(testSender),new ReportingAdminController(new ReportingSettings(env::get),service)).build();
            mvc.perform(get("/api/team/7")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(7));
            mvc.perform(get("/webhooks/whatsapp").queryParam("hub.mode","subscribe").queryParam("hub.verify_token","synthetic-verify").queryParam("hub.challenge","unchanged-challenge")).andExpect(status().isOk()).andExpect(content().string("unchanged-challenge"));
            byte[] body=signedBody(value("wamid.disabled","unrelated","wamid.context","button"));
            mvc.perform(post("/webhooks/whatsapp").contentType("application/json").header("X-Hub-Signature-256",signature(body)).content(body)).andExpect(status().isOk());
            mvc.perform(post("/api/whatsapp/test-send").header("X-WhatsApp-Test-Send-Secret",sendEnv.get("WHATSAPP_TEST_SEND_SECRET"))).andExpect(status().isAccepted());assertEquals(1,http.calls);
            mvc.perform(get(BASE+"/status").header(ADMIN,env.get("WHATSAPP_REPORTING_ADMIN_SECRET"))).andExpect(status().isOk()).andExpect(jsonPath("$.reportingEnabled").value(false));
            service.drainOne();new ReportingOutboxPump(new ReportingSettings(env::get),service).start();assertEquals(0,store.transactions.get());assertEquals(0,store.pendingQueries.get());assertEquals(0,sends.get());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'whatsapp_report_%'",Integer.class));
        } finally {testSender.close();}
    }

    @Test void secondMigrationPreservesLegacyInProgressAndSubmittedSelectionsAndIssuedPayloads() throws Exception {
        try(Connection c=dataSource.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/sql/001-whatsapp-reporting.sql"));}
        String id=UUID.randomUUID().toString(),out=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO whatsapp_report_requests(id,worker_id,recipient,phone_id,work_date,step,revision,current_site,outcome,created_at) VALUES(?,7,'970599000007','123400007',?,'COWORKERS',2,11,'WORKED',?)",id,DAY,java.sql.Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO whatsapp_report_sites VALUES(?,11,'Original snapshot')",id);jdbc.update("INSERT INTO whatsapp_report_coworkers VALUES(?,11,8,'Original coworker')",id);
        Request before=store.transaction(() -> store.request(id));String payload=new ReportingFlow(mapper).prompt(before,List.of(new Site(id,11,"Original snapshot")),List.of(),List.of(),List.of(new Choice(8,"coworker")),0);
        jdbc.update("INSERT INTO whatsapp_report_outbound(id,request_id,revision,kind,payload,state,meta_id,created_at) VALUES(?,?,2,'PROMPT',?,'ACCEPTED','wamid.legacyPrompt',?)",out,id,payload,java.sql.Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO whatsapp_report_inbox(event_key,message_id,kind,request_id,disposition,received_at) VALUES(?,'wamid.oldInbox','MESSAGE',?,'APPLIED',?)","e".repeat(64),id,java.sql.Timestamp.from(clock.instant()));
        String submitted=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO whatsapp_report_requests(id,worker_id,recipient,phone_id,work_date,step,revision,outcome,created_at,submitted_at) VALUES(?,8,'972599000008','123400007',?,'SUBMITTED',5,'WORKED',?,?)",submitted,DAY,java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO whatsapp_report_sites VALUES(?,12,'Submitted snapshot')",submitted);jdbc.update("INSERT INTO whatsapp_report_coworkers VALUES(?,12,7,'Submitted mention')",submitted);
        try(Connection c=dataSource.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("docs/sql/002-whatsapp-reporting-other-and-nicknames.sql"));}
        service.configureWorker(7,true,"970599000007");service.configureWorker(8,true,"972599000008");
        assertEquals("SUBMITTED",service.report(submitted).step());assertEquals("Submitted snapshot",service.report(submitted).sites().getFirst().label());assertEquals(12,service.report(submitted).coworkers().getFirst().entryId());
        assertEquals(payload,current(id).payload());assertEquals(11,service.report(id).sites().getFirst().entryId());assertEquals(11,service.report(id).coworkers().getFirst().entryId());assertEquals("Original snapshot",service.report(id).sites().getFirst().label());
        service.acceptVerifiedValue(value("wamid.legacyReply",ReportingFlow.token(before,"cow",8,0),"wamid.legacyPrompt","list_reply"));
        assertTrue(service.report(id).coworkers().isEmpty());assertEquals(3,store.transaction(() -> store.request(id)).revision());assertEquals(2,service.report(id).inbox().size());
        act(id,"done",0);act(id,"finish",0);act(id,"submit",0);assertEquals("AWAITING_REVIEW",service.report(id).reviewStatus());
    }
    @Test void databaseActivityCalendarBoundariesIgnoreManualFlagsAndMissingTodayAssignments() throws Exception {
        migrate();jdbc.update("DELETE FROM work_assignments");
        for(LocalDate day:List.of(DAY.minusDays(7),DAY.minusDays(6),DAY,DAY.plusDays(1))) {
            jdbc.update("DELETE FROM work_assignments");jdbc.update("INSERT INTO work_assignments(team_member_id,master_worksite_id,date) VALUES(7,11,?)",day);
            boolean expected=!day.isBefore(DAY.minusDays(6))&&!day.isAfter(DAY);
            assertEquals(expected,store.activeWorker(7,DAY));assertEquals(expected,!store.activeSites(DAY).isEmpty());
        }
        jdbc.update("DELETE FROM work_assignments");jdbc.update("INSERT INTO work_assignments(team_member_id,master_worksite_id,date) VALUES(7,11,?)",DAY.minusDays(6));financialSnapshot=financialState();
        service.configureWorker(7,true,"970599000007");assertEquals(1,store.activeSites(DAY).size());String id=initiate();dispatch(id);assertEquals(1,sends.get());
    }
    @Test void databaseNicknameFallbackSnapshotAndOldClientUpdate() throws Exception {
        configured();MasterWorksiteService masters=masterService();
        store.transaction(() -> masters.updateNickname(11L,"  لقب عربي  "));
        assertEquals("لقب عربي",store.activeSites(DAY).getFirst().label());String id=initiate();act(id,"start",0);String shown=ReportingFlow.siteLabel(store.activeSites(DAY).getFirst());
        store.transaction(() -> masters.updateNickname(11L,"لقب آخر"));act(id,"site",11);assertEquals(shown,service.report(id).sites().getFirst().label());assertEquals("موقع تجريبي أول",service.report(id).sites().getFirst().businessName());
        var old=store.transaction(() -> masters.getById(11L));old.setNickname(null);store.transaction(() -> masters.update(11L,old));
        assertEquals("لقب آخر",store.transaction(() -> masters.getById(11L)).getNickname());
        store.transaction(() -> masters.updateNickname(11L,""));assertEquals("موقع تجريبي أول",store.activeSites(DAY).getFirst().label());
        assertEquals("preserve existing notes",store.transaction(() -> masters.getById(11L)).getNotes());
    }
    @Test void databaseOtherOnlyAndMultipleOtherCoworkersPersistAcrossOrmRecreationAndMidnight() throws Exception {
        configured();String id=capture();description(id,"أول موقع آخر");act(id,"cow",8);act(id,"done",0);act(id,"other",0);dispatch(id);
        factory.destroy();bootstrapOrm();recreateServices();clock.now=Instant.parse("2026-10-23T00:30:00Z");
        description(id,"ثاني موقع آخر");act(id,"cow",8);act(id,"done",0);act(id,"finish",0);act(id,"submit",0);
        var report=service.report(id);assertEquals(DAY,report.workDate());assertEquals(2,report.sites().size());assertTrue(report.sites().stream().allMatch(s -> s.siteId()==null));assertNotEquals(report.coworkers().get(0).entryId(),report.coworkers().get(1).entryId());assertEquals("AWAITING_REVIEW",report.reviewStatus());
    }
    @Test void databaseOtherRemainsAvailableWithoutKnownActiveSites() throws Exception {
        configured();
        // Empty choice projection is injected; all conversation writes still use actual MySQL.
        // A valid legacy FK schema normally has at least the worker's recently assigned master.
        ReportingStore noChoices=new FaultStore(){@Override public List<Choice> activeSites(LocalDate d){return List.of();}};
        service=new ReportingService(noChoices,new ReportingSettings(env::get),p -> new ReportingSender.Result("ACCEPTED","wamid.emptySites"+sends.incrementAndGet()),clock,mapper);
        String id=capture();description(id,"موقع خارج القائمة");act(id,"done",0);act(id,"finish",0);act(id,"submit",0);assertEquals(1,service.report(id).sites().size());
    }
    @ParameterizedTest @ValueSource(booleans={true,false}) void databaseConcurrentOtherTextRollbackAndSignedRedeliveryCommitOnce(boolean sameId) throws Exception {
        configured();String id=capture();JsonNode event=text("wamid.otherRollback","وصف أصلي محفوظ",current(id).metaId());byte[] body=signedBody(event);MockMvc mvc=webhookMvc();
        store.failAt.set("INBOX");mvc.perform(post("/webhooks/whatsapp").contentType("application/json").header("X-Hub-Signature-256",signature(body)).content(body)).andExpect(status().isServiceUnavailable());
        assertEquals("OTHER_TEXT",service.report(id).step());assertTrue(service.report(id).sites().isEmpty());
        List<Runnable> jobs=new ArrayList<>();for(int i=0;i<6;i++){JsonNode concurrent=sameId ? event : text("wamid.distinctText"+i,"وصف أصلي محفوظ",current(id).metaId());jobs.add(() -> service.acceptVerifiedValue(concurrent));}assertTrue(race(jobs).isEmpty());assertEquals(1,service.report(id).sites().size());
        mvc.perform(post("/webhooks/whatsapp").contentType("application/json").header("X-Hub-Signature-256",signature(body)).content(body)).andExpect(status().isOk());assertEquals(1,service.report(id).sites().size());assertEquals("وصف أصلي محفوظ",service.report(id).sites().getFirst().originalDescription());
    }
    @Test void databaseTextContextAmbiguityAndConflictingContextNeverGuess() throws Exception {
        configured();String first=capture();clock.now=clock.now.plus(Duration.ofDays(1));String second=service.initiate(DAY.plusDays(1)).id();act(second,"start",0);act(second,"other",0);dispatch(second);
        service.acceptVerifiedValue(text("wamid.ambiguousText","غير واضح",null));service.acceptVerifiedValue(text("wamid.conflictText","غير واضح","wamid.unknown"));
        assertTrue(service.report(first).sites().isEmpty());assertTrue(service.report(second).sites().isEmpty());
        description(first,"تقرير أقدم");service.acceptVerifiedValue(text("wamid.singleCapture","تقرير أحدث",null));assertEquals(1,service.report(second).sites().size());assertEquals(DAY,service.report(first).workDate());
    }
    @Test void databaseUncertainTextRecoveryDoesNotResend() throws Exception {
        configured();String id=initiate();act(id,"start",0);act(id,"other",0);transport=p -> {sends.incrementAndGet();return new ReportingSender.Result("UNCERTAIN",null);};dispatch(id);int attempts=sends.get();
        service.acceptVerifiedValue(text("wamid.uncertainText","وصف متأخر","wamid.evidencedCapture"));assertTrue(service.report(id).sites().isEmpty());recreateServices();dispatch(id);assertEquals(attempts,sends.get());
        env.put("WHATSAPP_REPORTING_SEND_ENABLED","false");recreateServices();service.resolve(current(id).id(),"accepted","wamid.evidencedCapture");assertEquals(1,service.report(id).sites().size());assertEquals(attempts,sends.get());
    }
    @Test void databaseResolutionLinksOutsideWindowAndPreservesSubmissionWithIdempotency() throws Exception {
        String id=submittedOther();var original=service.report(id);long entry=original.sites().getFirst().entryId();ReportingResolutionService resolver=resolutions();
        var resolved=resolver.resolve(id,entry,13L,null);assertEquals(13L,resolved.resolvedMasterId());assertEquals("Unselected fixture site",resolved.businessName());assertEquals("قرب المدرسة القديمة",resolved.originalDescription());
        assertEquals(resolved,resolver.resolve(id,entry,13L,null));assertThrows(ReportingProblem.class,() -> resolver.resolve(id,entry,11L,null));
        var after=service.report(id);assertEquals(original.step(),after.step());assertEquals(original.submittedAt(),after.submittedAt());assertEquals(original.reviewStatus(),after.reviewStatus());assertEquals(original.coworkers(),after.coworkers());assertEquals(original.outbound(),after.outbound());
    }
    @Test void databaseNewMasterCreationAndLinkAreAtomicAndConcurrentRetriesCreateOnlyOnce() throws Exception {
        String id=submittedOther();long entry=service.report(id).sites().getFirst().entryId();int before=count("master_worksites");ReportingResolutionService resolver=resolutions();
        MasterWorksiteDTO dto=MasterWorksiteDTO.builder().approvedName("New explicit business master").nickname("اسم جديد").notes("Operator reviewed").build();
        store.failAt.set("RESOLUTION");assertThrows(DataAccessException.class,() -> resolver.resolve(id,entry,null,dto));assertEquals(before,count("master_worksites"));assertNull(service.report(id).sites().getFirst().resolvedMasterId());
        List<Runnable> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(() -> resolver.resolve(id,entry,null,MasterWorksiteDTO.builder().approvedName("New explicit business master").nickname("اسم جديد").notes("Operator reviewed").build()));
        var errors=race(jobs);assertTrue(errors.isEmpty(),() -> "Synthetic concurrent resolution errors: "+errors);assertEquals(before+1,count("master_worksites"));assertNotNull(service.report(id).sites().getFirst().resolvedMasterId());
    }
    @Test void databaseResolutionAdminProtectionAndValidation() throws Exception {
        String id=submittedOther();long entry=service.report(id).sites().getFirst().entryId();MockMvc mvc=MockMvcBuilders.standaloneSetup(new ReportingAdminController(new ReportingSettings(env::get),service,resolutions())).build();String path=BASE+"/requests/"+id+"/entries/"+entry+"/resolve";
        mvc.perform(post(path).contentType("application/json").content("{\"masterWorksiteId\":13}")).andExpect(status().isForbidden());
        mvc.perform(post(path).header(ADMIN,"wrong").contentType("application/json").content("{\"masterWorksiteId\":13}")).andExpect(status().isForbidden());
        mvc.perform(post(path).header(ADMIN,env.get("WHATSAPP_REPORTING_ADMIN_SECRET"),"duplicate").contentType("application/json").content("{\"masterWorksiteId\":13}")).andExpect(status().isForbidden());
        mvc.perform(post(path).header(ADMIN,env.get("WHATSAPP_REPORTING_ADMIN_SECRET")).contentType("application/json").content("{\"create\":{\"approvedName\":\" \"}}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).header(ADMIN,env.get("WHATSAPP_REPORTING_ADMIN_SECRET")).contentType("application/json").content("{\"masterWorksiteId\":13}")).andExpect(status().isOk()).andExpect(jsonPath("$.originalDescription").value("قرب المدرسة القديمة"));
    }

    private class FaultStore extends JdbcReportingStore {
        final AtomicReference<String> failAt=new AtomicReference<>();final AtomicBoolean abortAccepted=new AtomicBoolean();
        final AtomicInteger transactions=new AtomicInteger(),pendingQueries=new AtomicInteger();
        FaultStore(){super(jdbc,manager);}
        @Override public <T>T transaction(Supplier<T> action){transactions.incrementAndGet();return super.transaction(action);}
        @Override public List<String> pendingIds(int limit){pendingQueries.incrementAndGet();return super.pendingIds(limit);}
        @Override public void insertInbox(Inbox i){super.insertInbox(i);fail("INBOX");}
        @Override public void resolveSite(String request,long entry,long master,String key,Instant at){super.resolveSite(request,entry,master,key,at);fail("RESOLUTION");}
        @Override public void saveOutbound(Outbound o){
            super.saveOutbound(o);fail(o.state());
            if(o.state().equals("ACCEPTED") && abortAccepted.compareAndSet(true,false)) {
                try {DataSourceUtils.getConnection(dataSource).abort(Runnable::run);}
                catch(java.sql.SQLException e){throw new IllegalStateException("Synthetic interruption of this test transaction",e);}
            }
        }
        private void fail(String stage){
            String requested=failAt.get();
            if(stage.equals(requested) && failAt.compareAndSet(requested,null))jdbc.update("INSERT INTO reporting_forced_failure_missing_table(id) VALUES(1)");
        }
    }
}
