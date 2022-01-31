package com.uci.transformer.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uci.transformer.odk.entity.Assessment;
import com.uci.transformer.odk.entity.Question;
import com.uci.transformer.odk.entity.Question.QuestionType;
import com.uci.utils.telemetry.dto.*;
import com.uci.utils.telemetry.util.TelemetryEvents;

import io.r2dbc.postgresql.codec.Json;
import messagerosa.core.model.ButtonChoice;
import messagerosa.core.model.XMessagePayload;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AssessmentTelemetryBuilder {

	private static final String TELEMETRY_IMPL_VERSION = "3.0";
	private static final String QUESTION_TELEMETRY_IMPL_VERSION = "1.0";
	private static final String ACTOR_TYPE_USER = "User";
	private static final String DIKSHA_ORG = "DIKSHA";
	private static final String ASSESS_EVENT_MID = "fb3db9abceb578d8acbc812cdbd9c931";
	
	private static final String QUESTION_TYPE_MCQ = "mcq";
	private static final String QUESTION_TYPE_STRING = "string";
	private static final String ASSESS_EVENT_MID_PREFIX = "ASSESS:";
	// represents the MD5 Hash of "Individual Question-Responses -
	// Survey/Questionnaire"

	public String build(String botOrg, String channel, String provider, String producerID, String conversationOwnerID,
			Question question, Assessment assessment, XMessagePayload prevQuestionPayload, Question prevQuestion, long duration) {
		String questionType = getQuestionType(prevQuestionPayload.getButtonChoices());
		
		//Context Cdata
		List<Map<String, Object>> cdata = new ArrayList<>();
		Map<String, Object> map1 = new HashMap<>();
		map1.put("type", "ConversationOwner");
		map1.put("id", conversationOwnerID);
		Map<String, Object> map2 = new HashMap<>();
		map2.put("type", "Conversation");
		map2.put("id", assessment.getBotID().toString());
		cdata.add(map1);
		cdata.add(map2);
		
		//Context Rollup
		Map<String, String> rollup = new HashMap<>();
		rollup.put("l1", conversationOwnerID.toString()); //ConversationOwner value
		rollup.put("l2", assessment.getBotID().toString()); //Conversation value
		
		String channelName = (botOrg.equalsIgnoreCase("Anonymous")) || botOrg.isEmpty() ? DIKSHA_ORG : botOrg;
		String userID = "";
		try {
			userID = assessment.getUserID().toString();
		} catch (Exception e) {
		}
		
		//Context
		Context context = Context.builder()
								.channel(channelName)
								.env(channel + "." + provider)
								.pdata(Producer.builder()
												.id(getPdataId())
												.pid(producerID)
												.ver(QUESTION_TELEMETRY_IMPL_VERSION)
												.build())
								.did(userID)
								.sid("")
								.cdata(cdata)
								.rollup(rollup)
								.build();

		//Object rollup
		Map<String, String> questionRollup = new HashMap<>();
		questionRollup.put("l1", "BotOwnerID");
		questionRollup.put("l2", "BotID");
		questionRollup.put("l3", "QuestionID");
		
		//Object
		Target object = Target.builder().id(question.getId().toString()).type(question.getQuestionType().name())
				.ver(QUESTION_TELEMETRY_IMPL_VERSION).rollup(questionRollup).build();
		
		//Item
		Map<String, Object> itemDetails = new HashMap<>();
		itemDetails.put("botID", assessment.getBotID().toString());
		itemDetails.put("userID", userID.toString());
		itemDetails.put("id", (prevQuestion.getId() != null ? prevQuestion.getId().toString() : ""));
		itemDetails.put("type", questionType);
		itemDetails.put("mmc", new ArrayList());
		itemDetails.put("mc", new ArrayList());
		itemDetails.put("exlength", 0.0);
		itemDetails.put("maxscore", 1.0);
		itemDetails.put("title", prevQuestionPayload.getText());
		itemDetails.put("uri", "");
		itemDetails.put("desc", "");
		itemDetails.put("params", getItemParams(questionType, prevQuestionPayload.getButtonChoices()));
		
		/* Set Meta */
//		ObjectMapper mapper = new ObjectMapper();
//		JsonNode metaNode;
//		try {
//			metaNode = mapper.readValue(question.getMeta().asString(), new TypeReference<>() {});
//		} catch (JsonProcessingException e) {
//			metaNode = null;
//			System.out.println("Error in reading question meta json value");
//		}
//		itemDetails.put("meta", metaNode);
		
		//Edata
		Map<String, Object> edata = new HashMap<>();
		edata.put("duration", 0.0);
		edata.put("item", itemDetails);
		edata.put("resvalues", getEdataResValues(prevQuestionPayload.getButtonChoices(), assessment.getAnswer()));
		edata.put("score", 1.0);
		edata.put("pass", "Yes");
		edata.put("index", 1.0);
		
		/* Current Date Time */
        LocalDateTime localNow = LocalDateTime.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        String timestamp = fmt.format(localNow).toString();
        
        String mid = ASSESS_EVENT_MID_PREFIX+UUID.nameUUIDFromBytes(timestamp.getBytes()).toString().replace("-", "");
		
		//Telemetry
		Telemetry telemetry = Telemetry.builder()
										.eid(TelemetryEvents.ASSESS.getName())
										.ets(System.currentTimeMillis())
										.ver(TELEMETRY_IMPL_VERSION)
										.mid(mid)
										.actor(Actor.builder()
													.type(ACTOR_TYPE_USER)
													.id(userID)
													.build())
										.context(context)
										.object(object)
										.edata(edata)
										.type("event")
										.tags(new ArrayList())
										.flags(new ArrayList())
										.timestamp(timestamp)
										.build();
		
		return Telemetry.getTelemetryRequestData(telemetry);
	}
	
	/**
	 * Get pData id from env else default value
	 * @return String
	 */
	private String getPdataId() {
		if(System.getenv("TELEMETRY_EVENT_PDATA_ID") != null 
				&& !System.getenv("TELEMETRY_EVENT_PDATA_ID").isEmpty()) {
			return System.getenv("TELEMETRY_EVENT_PDATA_ID");
		}
		return "prod.uci.diksha";
	}
	
	/**
	 * Get question type(mcq/string) based on choices present or not
	 * @param questionChoices
	 * @return String
	 */
	private String getQuestionType(ArrayList<ButtonChoice> questionChoices) {
		String questionType;
		if(questionChoices != null && questionChoices.size() > 0) {
			questionType = QUESTION_TYPE_MCQ;
		} else {
			questionType = QUESTION_TYPE_STRING;
		}
		return questionType;
	}
	
	/**
	 * Get edata->item->params(aka: question choices) if exists for telemetry event
	 * @param questionType
	 * @param questionChoices
	 * @return List
	 */
	private List getItemParams(String questionType, ArrayList<ButtonChoice> questionChoices) {
		List params = new ArrayList();
		if(questionChoices != null) {
			for(ButtonChoice questionChoice : questionChoices) {
				Map<String, Object> param = new HashMap();
				Map<String, Object> text = new HashMap();
				text.put("text", questionChoice.getText());
				param.put(questionChoice.getKey(), text);
				params.add(param);
			}
		}
		return params;
	}
	
	/**
	 * Get edata res values for mcq/string type questions
	 * @param questionChoices
	 * @param answer
	 * @return
	 */
	private List getEdataResValues(ArrayList<ButtonChoice> questionChoices, String answer) {
		List values = new ArrayList();
		if(questionChoices != null && questionChoices.size() > 0) {
			for(ButtonChoice questionChoice : questionChoices) {
				if(questionChoice.getKey().equals(answer)) {
					Map<String, String> map = new HashMap();
					map.put("text", questionChoice.getText());
					Map<String, Object> map2 = new HashMap();
					map2.put(questionChoice.getKey(), map);
					values.add(map2);
					break;
				}
			}
		} else {
			Map<String, String> value1 = new HashMap<>();
			value1.put("ans1", answer);
			values.add(value1);
		}
		return values;
	}
}
