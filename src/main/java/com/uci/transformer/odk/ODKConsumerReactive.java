package com.uci.transformer.odk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Maps;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.uci.dao.models.XMessageDAO;
import com.uci.dao.repository.XMessageRepository;
import com.uci.transformer.TransformerProvider;
import com.uci.utils.service.UserService;
import com.uci.transformer.odk.entity.Assessment;
import com.uci.transformer.odk.entity.GupshupMessageEntity;
import com.uci.transformer.odk.entity.GupshupStateEntity;
import com.uci.transformer.odk.entity.Question;
import com.uci.transformer.odk.persistance.FormsDao;
import com.uci.transformer.odk.persistance.JsonDB;
import com.uci.transformer.odk.repository.AssessmentRepository;
import com.uci.transformer.odk.repository.MessageRepository;
import com.uci.transformer.odk.repository.QuestionRepository;
import com.uci.transformer.odk.repository.StateRepository;
import com.uci.transformer.odk.utilities.FormUpdation;
import com.uci.transformer.odk.utilities.FormInstanceUpdation;
import com.uci.transformer.telemetry.AssessmentTelemetryBuilder;
import com.uci.utils.CampaignService;
import com.uci.utils.cache.service.RedisCacheService;
import com.uci.utils.kafka.SimpleProducer;
import com.uci.utils.telemetry.service.PosthogService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import messagerosa.core.model.ButtonChoice;
import messagerosa.core.model.LocationParams;
import messagerosa.core.model.SenderReceiverInfo;
import messagerosa.core.model.Transformer;
import messagerosa.core.model.XMessage;
import messagerosa.core.model.XMessage.MessageState;
import messagerosa.core.model.XMessagePayload;
import messagerosa.xml.XMessageParser;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.client.RestTemplate;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.function.Tuple2;

import javax.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static messagerosa.core.model.XMessage.MessageState.NOT_SENT;
import static messagerosa.core.model.XMessage.MessageType.HSM;

@Component
@RequiredArgsConstructor
@Slf4j
public class ODKConsumerReactive extends TransformerProvider {

    private final Flux<ReceiverRecord<String, String>> reactiveKafkaReceiver;

    private static final String SMS_BROADCAST_IDENTIFIER = "Broadcast";
    public static final String XML_PREFIX = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    @Value("${outbound}")
    public String outboundTopic;

    @Value("${processOutbound}")
    private String processOutboundTopic;

    @Value("${telemetry}")
    public String telemetryTopic;

    @Autowired
    public SimpleProducer kafkaProducer;

    @Autowired
    QuestionRepository questionRepo;

    @Autowired
    AssessmentRepository assessmentRepo;

    @Autowired
    private StateRepository stateRepo;

    @Autowired
    private MessageRepository msgRepo;
    
    @Autowired
    XMessageRepository xMsgRepo;

    @Qualifier("custom")
    @Autowired
    private RestTemplate customRestTemplate;

    @Qualifier("rest")
    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    CampaignService campaignService;
    
    @Autowired
    UserService userService;

    @Value("${producer.id}")
    private String producerID;
    
    @Value("${assesment.character.go_to_start}")
    public String assesGoToStartChar;
    
    public MenuManager menuManager;
    
    public Boolean isStartingMessage;
    
    @Autowired
    public PosthogService posthogService;
    
    @Autowired
    public RedisCacheService redisCacheService;

    @EventListener(ApplicationStartedEvent.class)
    public void onMessage() {
        reactiveKafkaReceiver
                .doOnNext(new Consumer<ReceiverRecord<String, String>>() {
                    @Override
                    public void accept(ReceiverRecord<String, String> stringMessage) {
                        final long startTime = System.nanoTime();
                        try {
                            XMessage msg = XMessageParser.parse(new ByteArrayInputStream(stringMessage.value().getBytes()));
                            logTimeTaken(startTime, 1);
                            transform(msg)
                                    .subscribe(new Consumer<XMessage>() {
                                        @Override
                                        public void accept(XMessage transformedMessage) {
                                            logTimeTaken(startTime, 2);
                                            if (transformedMessage != null) {
                                                try {
                                                    kafkaProducer.send(processOutboundTopic, transformedMessage.toXML());
                                                    long endTime = System.nanoTime();
                                                    long duration = (endTime - startTime);
                                                    log.error("Total time spent in processing form: " + duration / 1000000);
                                                } catch (JAXBException e) {
                                                    e.printStackTrace();
                                                }
                                            }
                                        }
                                    });
                        } catch (JAXBException e) {
                            e.printStackTrace();
                        } catch (NullPointerException e) {
                            log.error("An error occured : "+e.getMessage() + " at line no : "+ e.getStackTrace()[0].getLineNumber()
                                    +" in class : "+e.getStackTrace()[0].getClassName());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                })
                .doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable e) {
                        System.out.println(e.getMessage());
                        log.error("KafkaFlux exception", e);
                    }
                }).subscribe();

    }
    
    @Override
    public Mono<XMessage> transform(XMessage xMessage) throws Exception {
    	XMessage[] finalXMsg = new XMessage[1];
        ArrayList<Transformer> transformers = xMessage.getTransformers();
        Transformer transformer = transformers.get(0);

        log.info("1 To User ID:"+xMessage.getTo().getUserID());
        String formID = ODKConsumerReactive.this.getTransformerMetaDataValue(transformer, "formID");

        if (formID.equals("")) {
            log.error("Unable to find form ID from Conversation Logic");
            return null;
        }

        log.info("current form ID:"+formID);
        String formPath = getFormPath(formID);
        log.info("current form path:"+formPath);
        if(formPath == null ){
            return null;
        }
        isStartingMessage = xMessage.getPayload().getText() == null ? false : xMessage.getPayload().getText().equals(getTransformerMetaDataValue(transformer, "startingMessage"));
        Boolean addOtherOptions = xMessage.getProvider().equals("sunbird") ? true : false;

        // Get details of user from database
        return getPreviousMetadata(xMessage, formID)
                .map(new Function<FormManagerParams, Mono<Mono<XMessage>>>() {
                    @Override
                    public Mono<Mono<XMessage>> apply(FormManagerParams previousMeta) {
                        final ServiceResponse[] response = new ServiceResponse[1];
                        MenuManager mm;
                        ObjectMapper mapper = new ObjectMapper();
                        JSONObject camp = null; //  is not being used in menumanager, only being added in constructor
                        // Remove camp from MenuManager construction

                        JSONObject user = userService.getUserByPhoneFromFederatedServers(
                                getTransformerMetaDataValue(transformer, "botId"),
                                xMessage.getTo().getUserID()
                        );
                        log.info("Federated User by phone : "+user);
//                        try {
//                            camp = new JSONObject(mapper.writeValueAsString(campaign));
//                        } catch (JsonProcessingException e) {
//                            e.printStackTrace();
//                        }
                        String hiddenFieldsStr = getTransformerMetaDataValue(transformer, "hiddenFields");
                        ArrayNode hiddenFields = null;
                        try{
                            hiddenFields = (ArrayNode) mapper.readTree(hiddenFieldsStr);
                            log.info("hiddenFields: "+hiddenFields);
                        } catch (Exception ex) {
                            log.error("Exception in hidden fields read: "+ex.getMessage());
//                            ex.printStackTrace();
                        }

                        String instanceXMlPrevious = "";
                        Boolean prefilled;
                        String answer;
                        if (previousMeta.instanceXMlPrevious == null || previousMeta.currentAnswer.equals(assesGoToStartChar) || isStartingMessage) {
//                                            if (!lastFormID.equals(formID) || previousMeta.instanceXMlPrevious == null || previousMeta.currentAnswer.equals(assesGoToStartChar) || isStartingMessage) {
                            previousMeta.currentAnswer = assesGoToStartChar;
                            ServiceResponse serviceResponse = new MenuManager(null,
                                    null, null, formPath, formID, false,
                                    questionRepo, redisCacheService, xMessage.getTo().getUserID(), xMessage.getApp(), null).start();
                            FormInstanceUpdation ss = FormInstanceUpdation.builder().build();
                            ss.parse(serviceResponse.currentResponseState);
                            ss.updateAdapterProperties(xMessage.getChannel(), xMessage.getProvider());
                            ss.updateParams("phone_number", xMessage.getTo().getUserID());
                            instanceXMlPrevious = ss.updateHiddenFields(hiddenFields, (JSONObject) user).getXML();
                            prefilled = false;
                            answer = null;
                            log.info("Condition 1 - xpath: null, answer: null, instanceXMlPrevious: "
                                    +instanceXMlPrevious+", formPath: "+formPath+", formID: "+formID);
                            mm = new MenuManager(null, null, instanceXMlPrevious,
                                    formPath, formID, redisCacheService, xMessage.getTo().getUserID(), xMessage.getApp(), xMessage.getPayload());
                            response[0] = mm.start();
                        } else {
                            FormInstanceUpdation ss = FormInstanceUpdation.builder().build();
                            if(previousMeta.previousPath.equals("question./data/group_matched_vacancies[1]/initial_interest[1]")){
                                ss.parse(previousMeta.instanceXMlPrevious);
                                ss.updateAdapterProperties(xMessage.getChannel(), xMessage.getProvider());

                                JSONObject vacancyDetails = null;
                                for(int j=0; j<user.getJSONArray("matched").length(); j++){
                                    String vacancyID = String.valueOf(user.getJSONArray("matched").getJSONObject(j).getJSONObject("vacancy_detail").getInt("id"));
                                    if(previousMeta.currentAnswer.equals(vacancyID)){
                                        vacancyDetails = user.getJSONArray("matched").getJSONObject(j).getJSONObject("vacancy_detail");
                                    }
                                }
                                ss.updateHiddenFields(hiddenFields, user);
                                int idToBeDeleted = -1;
                                for (int i=0; i< hiddenFields.size(); i++){
                                    JsonNode object = hiddenFields.get(i);
                                    String userField = object.findValue("name").asText();
                                    if(userField.equals("candidate_id")){
                                        idToBeDeleted = i;
                                    }
                                }
                                if(idToBeDeleted > -1) hiddenFields.remove(idToBeDeleted);
                                instanceXMlPrevious = instanceXMlPrevious + ss.updateHiddenFields(hiddenFields, (JSONObject) vacancyDetails).getXML();
                                prefilled = false;
                                answer = previousMeta.currentAnswer;
                                log.info("Condition 1 - xpath: "+previousMeta.previousPath+", answer: "+answer+", instanceXMlPrevious: "
                                        +instanceXMlPrevious+", formPath: "+formPath+", formID: "+formID+", prefilled: "+prefilled
                                        +", questionRepo: "+questionRepo+", user: "+user+", shouldUpdateFormXML: true, campaign: "+camp);
                                mm = new MenuManager(previousMeta.previousPath, answer,
                                        instanceXMlPrevious, formPath, formID,
                                        prefilled, questionRepo, user, true, redisCacheService, xMessage);
                            }else{
                                prefilled = false;
                                answer = previousMeta.currentAnswer;
                                instanceXMlPrevious = previousMeta.instanceXMlPrevious;
                                log.info("Condition 1 - xpath: "+previousMeta.previousPath+", answer: "+answer+", instanceXMlPrevious: "
                                        +instanceXMlPrevious+", formPath: "+formPath+", formID: "+formID+", prefilled: "+prefilled
                                        +", questionRepo: "+questionRepo+", user: "+user+", shouldUpdateFormXML: true, campaign: "+camp);
                                mm = new MenuManager(previousMeta.previousPath, answer,
                                        instanceXMlPrevious, formPath, formID,
                                        prefilled, questionRepo, user, true, redisCacheService, xMessage);
                            }
                            response[0] = mm.start();
                        }

                        log.info("next question xpath:" + response[0].question.getXPath());

                        /* To use with previous question & question payload methods */
//                                            log.info("menu manager instanceXMlPrevious: "+instanceXMlPrevious);
                        menuManager = mm;

                        /* Previous Question Data */
                        Question prevQuestion = null;
                        if(!isStartingMessage) {
                            prevQuestion = menuManager.getQuestionFromXPath(previousMeta.previousPath);
                        }

                        // Save answerData => PreviousQuestion + CurrentAnswer
                        Mono<Pair<Boolean, List<Question>>> updateQuestionAndAssessment =
                                updateQuestionAndAssessment(
                                        previousMeta,
                                        getPreviousQuestions(
                                                previousMeta.previousPath,
                                                formID,
                                                response[0].formVersion),
                                        formID,
                                        transformer,
                                        xMessage,
                                        response[0].question,
                                        prevQuestion,
                                        response[0].currentIndex
                                );


                        /* If form contains eof__, then process next bot by id addded with eof__bot_id, else process message */
                        if (response[0].currentIndex.contains("eof__")) {
                            String nextBotID = mm.getNextBotID(response[0].currentIndex);

                            return Mono.zip(
                                    campaignService.getBotNameByBotID(nextBotID),
                                    campaignService.getFirstFormByBotID(nextBotID)
                            ).map(new Function<Tuple2<String, String>, Mono<XMessage>>() {
                                @Override
                                public Mono<XMessage> apply(Tuple2<String, String> objects) {
                                    String nextFormID = objects.getT2();
                                    String nextAppName = objects.getT1();

                                    ServiceResponse serviceResponse = new MenuManager(
                                            null, null, null,
                                            getFormPath(nextFormID), nextFormID,
                                            false, questionRepo, redisCacheService, xMessage.getTo().getUserID(), xMessage.getApp(), null)
                                            .start();
                                    FormInstanceUpdation ss = FormInstanceUpdation.builder().build();
                                    ss.parse(serviceResponse.currentResponseState);
                                    ss.updateAdapterProperties(xMessage.getChannel(), xMessage.getProvider());
//                                                        String instanceXMlPrevious = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
//                                                                ss.getXML();
                                    String instanceXMlPrevious = ss.getXML();
                                    log.debug("Instance value >> " + instanceXMlPrevious);
                                    MenuManager mm2 = new MenuManager(null, null,
                                            instanceXMlPrevious, getFormPath(nextFormID), nextFormID, true,
                                            questionRepo, redisCacheService, xMessage.getTo().getUserID(), xMessage.getApp(), null);
                                    ServiceResponse response = mm2.start();
                                    xMessage.setApp(nextAppName);
                                    return decodeXMessage(xMessage, response, nextFormID, updateQuestionAndAssessment);
                                }
                            });
                        } else {
                            return Mono.just(decodeXMessage(xMessage, response[0], formID, updateQuestionAndAssessment));
                        }
                    }
                }).flatMap(new Function<Mono<Mono<XMessage>>, Mono<XMessage>>() {
                    @Override
                    public Mono<XMessage> apply(Mono<Mono<XMessage>> m) {
                        log.info("Level 1");
                        return m.flatMap(new Function<Mono<XMessage>, Mono<? extends XMessage>>() {
                            @Override
                            public Mono<? extends XMessage> apply(Mono<XMessage> n) {
                                log.info("Level 2");
                                return n;
                            }
                        });
                    }
                });
    }
    
    /**
     * Check if form has ended by xpath
     * @param xPath
     * @return
     */
    private Boolean isEndOfForm(String xPath) {
    	log.info("xPath for isEndOfForm check: "+xPath);
    	return xPath.contains("endOfForm") || xPath.contains("eof");
    }

    private Mono<FormManagerParams> getPreviousMetadata(XMessage message, String formID) {
        String prevPath = null;
        String prevXMl = null;
        FormManagerParams formManagerParams = new FormManagerParams();

        if (!message.getMessageState().equals(XMessage.MessageState.OPTED_IN)) {
            return stateRepo.findByPhoneNoAndBotFormName(message.getTo().getUserID(), formID)
                    .defaultIfEmpty(new GupshupStateEntity())
                    .map(new Function<GupshupStateEntity, FormManagerParams>() {
                        @Override
                        public FormManagerParams apply(GupshupStateEntity stateEntity) {
                            String prevXMl = null, prevPath = null;
                            if (stateEntity != null && message.getPayload() != null) {
                                prevXMl = stateEntity.getXmlPrevious();
                                prevPath = stateEntity.getPreviousPath();
                            }

                            // Handle image responses to a question
                            if (message.getPayload() != null) {
                            	if(message.getPayload().getMedia() != null) {
                                	formManagerParams.setCurrentAnswer(message.getPayload().getMedia().getUrl());
                                } else if(message.getPayload().getLocation() != null) {
                                	formManagerParams.setCurrentAnswer(getLocationContentText(message.getPayload().getLocation()));
                                } else  {
                                	formManagerParams.setCurrentAnswer(message.getPayload().getText());
                                }
                            } else {
                                formManagerParams.setCurrentAnswer("");
                            }
                            formManagerParams.setPreviousPath(prevPath);
                            formManagerParams.setInstanceXMlPrevious(prevXMl);
                            return formManagerParams;
                        }
                    })
                    .doOnError(e -> log.error(e.getMessage()));
        } else {
            formManagerParams.setCurrentAnswer("");
            formManagerParams.setPreviousPath(prevPath);
            formManagerParams.setInstanceXMlPrevious(prevXMl);
            return Mono.just(formManagerParams);
        }
    }

    /**
     * Get location content text
     * @param location
     * @return
     */
    private String getLocationContentText(LocationParams location) {
    	String text = "";
    	text = location.getLatitude()+" "+location.getLongitude();
    	if(location.getAddress() != null && !location.getAddress().isEmpty()) {
    		text += " "+location.getAddress();
    	}
    	if(location.getName() != null && !location.getName().isEmpty()) {
    		text += " "+location.getName();
    	}
    	if(location.getUrl() != null && !location.getUrl().isEmpty()) {
    		text += " "+location.getUrl();
    	}
    	return text.trim();
    }
    
    @NotNull
    private Mono<Pair<Boolean, List<Question>>> updateQuestionAndAssessment(FormManagerParams previousMeta,
                                                                            Mono<Pair<Boolean, List<Question>>> previousQuestions, String formID,
                                                                            Transformer transformer, XMessage xMessage, Question question, Question prevQuestion,
                                                                            String currentXPath) {
        return previousQuestions
                .doOnNext(new Consumer<Pair<Boolean, List<Question>>>() {
                    @Override
                    public void accept(Pair<Boolean, List<Question>> existingQuestionStatus) {
                        if (existingQuestionStatus.getLeft()) {
                        	log.info("Found Question id: "+existingQuestionStatus.getRight().get(0).getId()+", xPath: "+existingQuestionStatus.getRight().get(0).getXPath());
                        	saveAssessmentData(
                                    existingQuestionStatus, formID, previousMeta, transformer, xMessage, null, currentXPath).subscribe(new Consumer<Assessment>() {
                                @Override
                                public void accept(Assessment assessment) {
                                    log.info("Assessment Saved Successfully {}", assessment.getId());
                                }
                            });
                        } else {
                        	Question saveQuestion;
                        	if(prevQuestion == null) {
                        		saveQuestion = question;
                        	} else {
                        		saveQuestion = prevQuestion;
                        	}
                            saveQuestion(saveQuestion).subscribe(new Consumer<Question>() {
                                @Override
                                public void accept(Question question) {
                                	log.info("Question Saved Successfully, id: "+question.getId()+", xPath: "+question.getXPath());
                                	saveAssessmentData(
                                            existingQuestionStatus, formID, previousMeta, transformer, xMessage, question, currentXPath).subscribe(new Consumer<Assessment>() {
                                        @Override
                                        public void accept(Assessment assessment) {
                                            log.info("Assessment Saved Successfully {}", assessment.getId());
                                        }
                                    });
                                }
                            });
                        }
                    }
                });
    }

    private Mono<Pair<Boolean, List<Question>>> getPreviousQuestions(String previousPath, String formID, String formVersion) {
    	return questionRepo
                .findQuestionByXPathAndFormIDAndFormVersion(previousPath, formID, formVersion)
                .collectList()
                .flatMap(new Function<List<Question>, Mono<Pair<Boolean, List<Question>>>>() {
                    @Override
                    public Mono<Pair<Boolean, List<Question>>> apply(List<Question> questions) {
                        Pair<Boolean, List<Question>> response = Pair.of(false, new ArrayList<Question>());
                        if (questions != null && questions.size() > 0) {
                            response = Pair.of(true, questions);
                        }
                        return Mono.just(response);
                    }
                });
    }

    private Mono<Question> saveQuestion(Question question) {
        return questionRepo.save(question);
    }

    private Mono<Assessment> saveAssessmentData(Pair<Boolean, List<Question>> existingQuestionStatus,
                                                String formID, FormManagerParams previousMeta,
                                                Transformer transformer, XMessage xMessage, Question question,
                                                String currentXPath) {
        if (question == null) question = existingQuestionStatus.getRight().get(0);
        
        UUID userID = xMessage.getTo().getDeviceID() != null && !xMessage.getTo().getDeviceID().isEmpty() && xMessage.getTo().getDeviceID() != "" ? UUID.fromString(xMessage.getTo().getDeviceID()) : null;
        log.info("User uuid:"+userID);

        Assessment assessment = Assessment.builder()
                .question(question)
                .deviceID(userID)
                .answer(previousMeta.currentAnswer)
                .botID(UUID.fromString(getTransformerMetaDataValue(transformer, "botId")))
                .userID(userID)
                .build();
        try {
        	if(question != null) {
        		log.info("In saveAssessmentData, question id: "+question.getId()+", question xpath: "+question.getXPath());
        	}else {
            	log.info("In saveAssessmentData, Question empty: "+question);
            }
        	
        	if(question != null && !isStartingMessage) {
        		
        		XMessagePayload questionPayload = menuManager.getQuestionPayloadFromXPath(question.getXPath());
        		
                log.info("find xmessage by app: "+xMessage.getApp()+", userId: "+xMessage.getTo().getUserID()+", fromId: admin, status: "+MessageState.SENT.name());
                
                /* Get Previous question XMessage */
                getLatestXMessage(xMessage.getApp(), xMessage.getTo().getUserID())
                    .subscribe(new Consumer<XMessageDAO>() {
                        @Override
                        public void accept(XMessageDAO xMsgDao) {
                        	log.info("found xMsgDao");
                        	
                            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                            
                            /* local date time */
                            LocalDateTime localNow = LocalDateTime.now();
                            String current = fmt.format(localNow).toString();
                            LocalDateTime currentDateTime = LocalDateTime.parse(current, fmt); 
                            LocalDateTime timestamp = xMsgDao.getTimestamp();
                            long diff_milis = ChronoUnit.MILLIS.between(timestamp, currentDateTime);
                            long diff_secs = ChronoUnit.SECONDS.between(timestamp, currentDateTime);
                            
                            log.info(
                            		"xMsgDao Id: "+xMsgDao.getId()+", userId: "+xMsgDao.getUserId()+", Timestamp: "+xMsgDao.getTimestamp()
                            		+", XMessage stylingTag: "+questionPayload.getStylingTag()+", flow: "+questionPayload.getFlow()+", index: "+questionPayload.getQuestionIndex()
                            		+", currentDateTime: "+currentDateTime+", timestamp: "+timestamp+", diff seconds: "+diff_secs+", diff millis: "+diff_milis);
                            
                            if(questionPayload.getFlow() != null 
                            	&& !questionPayload.getFlow().isEmpty()
                        		&& questionPayload.getQuestionIndex() != null) {
                            	posthogService.sendDropOffEvent(
                            			xMsgDao.getUserId(), questionPayload.getFlow().toString(), 
                            			questionPayload.getQuestionIndex(), diff_milis)
                            	.subscribe(new Consumer<String>() {
									@Override
									public void accept(String t) {
										// TODO Auto-generated method stub
										log.info("telemetry response: "+t);
									}
                            	});
                            } else {
                            	log.info("Posthog telemetry event not being sent for flow: "+questionPayload.getFlow()+", index: "+questionPayload.getQuestionIndex());
                            }
                        }
                    });
                
        		saveTelemetryEvent(transformer, xMessage, assessment, questionPayload, currentXPath);

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.info("question xpath:"+question.getXPath()+",answer: "+assessment.getAnswer());
        
        return assessmentRepo.save(assessment)
                .doOnError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        log.error(throwable.getMessage());
                    }
                })
                .doOnNext(new Consumer<Assessment>() {
                    @Override
                    public void accept(Assessment assessment) {
                        log.info("Assessment Saved by id: "+assessment.getId());
                    }
                });
    }

    private void saveTelemetryEvent(Transformer transformer, XMessage xMessage, Assessment assessment, XMessagePayload questionPayload, String currentXPath) throws Exception {
        String telemetryEvent = new AssessmentTelemetryBuilder()
                .build(getTransformerMetaDataValue(transformer, "botOwnerOrgID"),
                        xMessage.getChannel(),
                        xMessage.getProvider(),
                        producerID,
                        getTransformerMetaDataValue(transformer, "botOwnerOrgID"),
                        assessment.getQuestion(),
                        assessment,
                        questionPayload,
                        0,
                        xMessage.getTo().getEncryptedDeviceID(),
                        xMessage.getMessageId().getChannelMessageId(),
                        isEndOfForm(currentXPath));
        System.out.println(telemetryEvent);
        kafkaProducer.send(telemetryTopic, telemetryEvent);
        posthogService.sendTelemetryEvent(xMessage.getTo().getUserID(), telemetryEvent).subscribe(new Consumer<String>() {
            @Override
            public void accept(String t) {
                // TODO Auto-generated method stub
                log.info("telemetry response: " + t);
            }
        });
    }
    
    private Flux<XMessageDAO> getLatestXMessage(String appName, String userID) {
    	try {
            XMessageDAO xMessageDAO = (XMessageDAO)redisCacheService.getXMessageDaoCache(userID);
    	  	if(xMessageDAO != null) {
    	  		log.info("redis key: "+redisKeyWithPrefix("XMessageDAO")+", "+redisKeyWithPrefix(userID));
    	  		log.info("Redis xMsgDao id: "+xMessageDAO.getId()+", dao app: "+xMessageDAO.getApp()
    			+", From id: "+xMessageDAO.getFromId()+", user id: "+xMessageDAO.getUserId()
    			+", xMessage: "+xMessageDAO.getXMessage()+", status: "+xMessageDAO.getMessageState()+
    			", timestamp: "+xMessageDAO.getTimestamp());
    	  		return Flux.just(xMessageDAO);
    	  	} else {
    	  		log.info("not found in redis for key: "+redisKeyWithPrefix("XMessageDAO")+", "+redisKeyWithPrefix(userID));
    	  	}
    	} catch (Exception e) {
    		log.error("Exception in redis data fetch: "+e.getMessage());   	
    	}
    	
    	return xMsgRepo.findFirstByAppAndUserIdAndFromIdAndMessageStateOrderByTimestampDesc(appName, userID, "admin", MessageState.SENT.name());
    }

    private Mono<XMessage> decodeXMessage(XMessage xMessage, ServiceResponse response, String formID, Mono<Pair<Boolean, List<Question>>> updateQuestionAndAssessment) {
        XMessage nextMessage = getMessageFromResponse(xMessage, response);
        if (isEndOfForm(response)) {
            return Mono.zip(
                    appendNewResponse(formID, xMessage, response),
                    replaceUserState(formID, xMessage, response),
                    updateQuestionAndAssessment,
                    Mono.just(new UploadService().submit(response.currentResponseState, restTemplate, customRestTemplate))
            )
                    .then(Mono.just(getClone(nextMessage)));
        } else {
            return Mono.zip(
                    appendNewResponse(formID, xMessage, response),
                    replaceUserState(formID, xMessage, response),
                    updateQuestionAndAssessment
            )
                    .then(Mono.just(getClone(nextMessage)));
        }
    }

    private boolean isEndOfForm(ServiceResponse response) {
        return response.getCurrentIndex().equals("endOfForm") || response.currentIndex.contains("eof");
    }

    /**
     * Get Meta data value by key in a transformer
     * @param transformer
     * @param key
     * @return meta data value
     */
    private String getTransformerMetaDataValue(Transformer transformer, String key) {
        Map<String, String> metaData = transformer.getMetaData();
        if(metaData.get(key) != null && !metaData.get(key).toString().isEmpty()) {
            return metaData.get(key).toString();
        }
        return "";
    }

    @Nullable
    private XMessage getClone(XMessage nextMessage) {
        XMessage cloneMessage = null;
        try {
            cloneMessage = XMessageParser.parse(new ByteArrayInputStream(nextMessage.toXML().getBytes()));
        } catch (JAXBException e) {
            e.printStackTrace();
        }
        return cloneMessage;
    }

    private XMessage getMessageFromResponse(XMessage xMessage, ServiceResponse response) {
        XMessagePayload payload = response.getNextMessage();
        xMessage.setPayload(payload);
        xMessage.setConversationLevel(response.getConversationLevel());
        return xMessage;
    }

    public static String getFormPath(String formID) {
    	FormsDao dao = new FormsDao(JsonDB.getInstance().getDB());
        try{
            return dao.getFormsCursorForFormId(formID).getFormFilePath();
        } catch (NullPointerException ex){
            log.info("ODK form not found '"+formID+"'");
            return null;
        }
    }

    private Mono<GupshupMessageEntity> appendNewResponse(String formID, XMessage xMessage, ServiceResponse response) {
        GupshupMessageEntity msgEntity = new GupshupMessageEntity();
        msgEntity.setPhoneNo(xMessage.getTo().getUserID());
        msgEntity.setMessage(xMessage.getPayload().getText());
        msgEntity.setLastResponse(response.getCurrentIndex().equals("endOfForm"));
        return msgRepo.save(msgEntity);
    }

    private Mono<GupshupStateEntity> replaceUserState(String formID, XMessage xMessage, ServiceResponse response) {
        log.info("Saving State");
        return stateRepo.findByPhoneNoAndBotFormName(xMessage.getTo().getUserID(), formID)
                .defaultIfEmpty(new GupshupStateEntity())
                .map(new Function<GupshupStateEntity, Mono<GupshupStateEntity>>() {
                    @Override
                    public Mono<GupshupStateEntity> apply(GupshupStateEntity saveEntity) {
                        log.info("Saving the ", xMessage.getTo().getUserID());
                        saveEntity.setPhoneNo(xMessage.getTo().getUserID());
                        saveEntity.setPreviousPath(response.getCurrentIndex());
                        saveEntity.setXmlPrevious(response.getCurrentResponseState());
                        saveEntity.setBotFormName(formID);
                        return stateRepo.save(saveEntity)
                                .doOnError(new Consumer<Throwable>() {
                                    @Override
                                    public void accept(Throwable throwable) {
                                        log.error("Unable to persist state entity {}", throwable.getMessage());
                                    }
                                }).doOnNext(new Consumer<GupshupStateEntity>() {
                                    @Override
                                    public void accept(GupshupStateEntity gupshupStateEntity) {
                                        log.info("Successfully persisted state entity");
                                    }
                                });
                    }
                }).flatMap(new Function<Mono<GupshupStateEntity>, Mono<? extends GupshupStateEntity>>() {
                    @Override
                    public Mono<? extends GupshupStateEntity> apply(Mono<GupshupStateEntity> gupshupStateEntityMono) {
                        return gupshupStateEntityMono;
                    }
                });

    }

    private void logTimeTaken(long startTime, int checkpointID) {
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        log.info(String.format("CP-%d: %d ms", checkpointID, duration));
    }
    
    private String redisKeyWithPrefix(String key) {
    	return System.getenv("ENV")+"-"+key;
    }
}
