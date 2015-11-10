package com.ca.asynchmsg.initializer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.servlet.ServletContext;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.ISO87APackager;

import com.ca.asynchmsg.beans.ConnectionBean;
import com.ca.asynchmsg.connectionset.ConnectionPool;
import com.ca.asynchmsg.container.ServerContainer;
import com.ca.asynchmsg.logger.AsynchLogger;
import com.ca.asynchmsg.message.Message;
import com.ca.asynchmsg.message.MessageUID;
import com.ca.asynchmsg.message.MessageUIDGenerator;
import com.ca.asynchmsg.message.MessageUIDGeneratorFactory;
import com.ca.asynchmsg.messageprocessor.MessageProcessor;

/**
 * 
 * @author upara01
 * ATMSwitchTCPHandler is an entry point of a request coming from outside of client-infra and also initializes a connection while context initialization.
 */
public class ATMSwitchTCPHandler {

	public static Logger logger = null;
	private static final String atmSwitchConfigFileName = "atmswitchconfig.json";
	private static final String logFileLocationKey = "logFileLocation";
	private static final String serverConfigKey = "serverConfig";
	private static final String serverHostKey = "host";
	private static final String portKey = "port";
	private static final String connTimeOutKey = "connTimeOut";
	private static final String readTimeOutKey = "readTimeOut";
	private static final String threadTimeOutKey = "threadTimeOut";
	private static final String retryKey = "retry";
	private static final String loglevelKey = "logLevel";
	private static final String echoTimeIntervalKey = "echoTimeInterval";
	private static Attributes manifestAttrs = null;
	public static ConcurrentHashMap<String,Boolean> manualStopPool = new ConcurrentHashMap<String, Boolean>();;
	
	/**
	 * initalizeConnections(ServletContext) : Initializes all the back end connections
	 * @param context : ServletContext
	 */
	public static void initalizeConnections(ServletContext context) {

		try {
			
			InputStream inputStream = context.getResourceAsStream("/META-INF/MANIFEST.MF");
			if (inputStream != null) {
				Manifest manifest = new Manifest(inputStream);

				if (manifest != null) {
					manifestAttrs = manifest.getMainAttributes();
				}
			}
			loadATMSwitchConfig("");
			
		} catch (Exception e) {
			System.out.println("ATM Switch Interface Initialization Issue...."+ e);
			if(logger !=null){
				logger.error("ATM Switch Interface Initialization Issue....", e);
			}
			return;
		}
		return;

	}
	private static boolean loadATMSwitchConfig(String connId) throws Exception{
		
		InputStream is = null;
		boolean isLoadedFlag = false;
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		is = classLoader.getResource(atmSwitchConfigFileName).openStream();
		
		if(is == null){
			throw new Exception("Unable to load config file - " + atmSwitchConfigFileName);
		}
		
		try {
			JsonReader jsonReader = Json.createReader(is);
			JsonObject allConfigs  = jsonReader.readObject();
			JsonString logFileLocation = allConfigs.getJsonString(logFileLocationKey);
			if(logFileLocation == null){
				throw new Exception(logFileLocationKey + " parameter missing in the configuration....");
			}
			if("".equals(connId))
				logger = (new AsynchLogger(logFileLocation.toString() +"/ISOSysLog", Level.INFO)).getLogger("ISOSysLog");
			
			JsonArray serverConfigs = allConfigs.getJsonArray(serverConfigKey);
			if(serverConfigs == null){
				throw new Exception( serverConfigKey +" parameter missing. No server Configurations specified....");
			}
			logger.info("Initializing ATM Switch Configuration....");
			
			if(manifestAttrs!= null && "".equals(connId)){
				
				logger.info("Build version "+ manifestAttrs.getValue("Implementation-Version") + " Build Date "+ manifestAttrs.getValue("Build-Time"));
			}
			
			for(int i =0;i<serverConfigs.size();i++){

				JsonObject serverConfig = serverConfigs.getJsonObject(i);
				JsonString host = serverConfig.getJsonString(serverHostKey);
				JsonNumber port = serverConfig.getJsonNumber(portKey);
				JsonNumber connTimeOut = serverConfig.getJsonNumber(connTimeOutKey);
				JsonNumber readTimeOut = serverConfig.getJsonNumber(readTimeOutKey);
				JsonNumber retry = serverConfig.getJsonNumber(retryKey);
				JsonNumber threadTimeOut = serverConfig.getJsonNumber(threadTimeOutKey);
				JsonString logLevelJson = serverConfig.getJsonString(loglevelKey);
				JsonNumber echoTimeInterval = serverConfig.getJsonNumber(echoTimeIntervalKey);
				JsonString uidFormat = serverConfig.getJsonString("uidFormat");
				
				if(host == null || port == null) {
					logger.error( serverConfig + " Bad configuration for connection ( Host OR Port is missing ). Not Loading this connection....");
					continue;
				}
				if(uidFormat == null){
					logger.error( serverConfig + " Bad configuration for connection ( uidFormat is missing ). Not Loading this connection....");
					continue;
				}
				
				Level level= null;
				if(logLevelJson == null){
					level = Level.INFO;
				}else {
					String logLevel = logLevelJson.getString();
					if (logLevel.equalsIgnoreCase("TRACE")){
						level = Level.TRACE;
					}else if (logLevel.equalsIgnoreCase("DEBUG")){
						level = Level.DEBUG;
					}else{
						level = Level.INFO;
					}
				}
				MessageUIDGenerator messageUIDGenerator = MessageUIDGeneratorFactory.getMessageUIDGenerator(uidFormat.toString());
				if(!"".equals(connId)){
					if(!connId.equalsIgnoreCase(host.getString()+"_"+port.intValue())){
						continue;
					}
				}
				ATMSwitchTCPHandler.manualStopPool.put(host.getString()+"_"+String.valueOf(port.intValue()),Boolean.FALSE);
				ConnectionBean bean = new ConnectionBean(host.getString(), port.intValue(), 
						(connTimeOut==null?8000:connTimeOut.intValue()), (readTimeOut==null?8000:readTimeOut.intValue()), (threadTimeOut==null?5000:threadTimeOut.intValue()),
						(retry==null?3:retry.intValue()), (echoTimeInterval==null?0:echoTimeInterval.intValue()));
				
				ServerContainer container = new ServerContainer(bean, logFileLocation.getString(), level, messageUIDGenerator);
				try {
					if (container.connect(false)){
						logger.info(bean.toString() + " Loaded....");
						isLoadedFlag = true;
					}else{
						logger.warn(bean.toString() + " could not be loaded....");
					}
					} catch (InterruptedException e) {
						logger.error("( "+bean.getUniqueName()+" ,loadAllClientConnection(, ,) ) Interrupted Exception", e);
					} catch (ExecutionException e) {
						logger.error("( "+bean.getUniqueName()+" ,loadAllClientConnection(, ,) ) ExecutionException", e);
					} catch (IOException e) {
						logger.error("( "+bean.getUniqueName()+" ,loadAllClientConnection(, ,) ) IOException", e);
					} catch (Exception e) {
						logger.error("( "+bean.getUniqueName()+" ,loadAllClientConnection(, ,) ) Exception", e);
					}				
			}
			
		}finally{
			if(is != null){
				try{
					is.close();
				}catch(IOException e){
					logger.error("IOException in closing the stream for JSON config property file....", e);
				}
			}
		}
		return isLoadedFlag;
	}

	/**
	 * processRequest(String, String) : Processes request.
	 * @param connId : The back end connection for which request to be processed.
	 * @param request : ISO Request
	 * @return Returns the response after processing the message.
	 */
	public String processRequest(String connId, String request) {
		String finalResponse=null;
		try {
			if("reconnectCall".equalsIgnoreCase(request)){
				logger.warn("( "+connId+" ) reconnectCall triggered....");
				finalResponse = reconnectBackend(connId);
			}else{
				finalResponse = getResponse(connId, request, "");
			}

		} catch (Exception e) {
			logger.error("( "+connId+" ,processRequest(,) ) Exception", e);
		}
		return finalResponse;		
	}
	
	/**
	 * processRequest(String, String) : Processes request.
	 * @param connId : The back end connection for which request to be processed.
	 * @param request : ISO Request
	 * @param nwMessageUID : the network message id i.e stan.
	 * @return Returns the response after processing the message.
	 */
	public String processRequest(String connId, String request, String nwMessageUID) throws Exception {
		String finalResponse=null;
		finalResponse =  getResponse(connId, request, nwMessageUID);
		return finalResponse;
	}
	
	private String getResponse(String connId, String request, String nwMessageUID) throws Exception{		
		Message msg =null;
		Message respMsg = null;
		ServerContainer containerHandle = ConnectionPool.getHandleToContainer(connId);
		MessageProcessor processMsg = null;
		try{
			if(containerHandle !=null){
				processMsg = containerHandle.getMessageProcessor();
			}
			else{				
				if(!ATMSwitchTCPHandler.manualStopPool.get(connId)){
					logger.warn("( "+connId+" ,getResponse(,) ) No Processor found for this message. Tring to connect Again......");
					loadATMSwitchConfig(connId);
					containerHandle = ConnectionPool.getHandleToContainer(connId);					
				}
				if(containerHandle !=null){
					processMsg = containerHandle.getMessageProcessor();
				}else{
					logger.error("( "+connId+" ,getResponse(,) ), There is no Processor found for this message....");
					return null;
				}
			}
			MessageUID messageUID = new MessageUID();
			//Preparing a message Object to hold request and message unique id (here Message Unique Id is STAN of the ISO request).	
			if(!"".equalsIgnoreCase(nwMessageUID)){
				String messageUIDStr = nwMessageUID;
				messageUID.setMaskedUID(messageUIDStr);
				messageUID.setUID(messageUIDStr);
				
			}else {
				ISOMsg  res = new ISOMsg();
				res.setPackager(new ISO87APackager());
				res.unpack(request.getBytes());
				MessageUIDGenerator messageUIDGenerator = containerHandle.getMessageUIDGenerator();
				if(messageUIDGenerator != null) {
					messageUID = messageUIDGenerator.generateMessageUID(res);
				}
			}
			logger.info("( "+connId+" ,getResponse(, ,) ) Request message UID : "+ messageUID.getMaskedUID());
			msg = new Message(request,messageUID);
			
			Date startDate = new Date();

			//Sending Message
			processMsg.deliverMessageToThirdParty(containerHandle, msg);
			
			//Requesting for Response
			respMsg = processMsg.retrieveMessageFromThirdParty(containerHandle,msg);
			
			Date endDate = new Date();
			
			String respUID = (respMsg !=null)?respMsg.getMessageUID().getMaskedUID():"NOTRCVD";
			if ("NOTRCVD".equals(respUID)){
				logger.warn("( "+connId+" ,getResponse(, ,) ) Round Robin Time [ "+((endDate.getTime() - startDate.getTime())/1000)+" ] Req [ "+ messageUID.getMaskedUID()+" ] Res [ NOTRCVD ]");
			}else{
				logger.info("( "+connId+" ,getResponse(, ,) ) Round Robin Time [ "+((endDate.getTime() - startDate.getTime())/1000)+" ] Req [ "+ messageUID.getMaskedUID()+" ] Res [ "+respUID+" ]");
			}

			
		}catch (IOException e) {
			//Catching an IO exception and calling re-connect.
			logger.error(" ( "+connId+" ,getResponse(, ,) ) IOException ",e);
			//containerHandle.reConnect(connId);
			
		}if(respMsg == null){
			return null;
		}else
			return respMsg.getMessageContents();
	}
	
	private String reconnectBackend(String connId){
		StringBuffer strBldr = new StringBuffer(""); 
		try {
			//Clean-up resouces for the specific connection Id.
			cleanUpResources(connId,false);
			if(loadATMSwitchConfig(connId)){
				strBldr.append(" ISO Backend Refresh successfully for connection id: "+connId+"....");
			}else{
				strBldr.append(" ISO Backend Refresh Failed for connection id: "+connId+"....");
			}			
		} catch (Exception e) {
			strBldr.append(" Restart Unsuccessful for connection id: "+connId+"....");
		}
		
		return strBldr.toString();
	}

	/**
	 * Closing all the connections one by one from the connection Map
	 */
	public static void closeConnections() {
		StringBuffer strBuf = new StringBuffer();
		logger.warn("ATM Switch Interface Shutting Down....");

		try {
			ConcurrentHashMap<String, ServerContainer> ATMSWITCH_CONN_MAP = ConnectionPool.getConnectionMap();
			Set connSet = ATMSWITCH_CONN_MAP.keySet();
			Iterator It = connSet.iterator();

			while (It.hasNext()) {
				String connName = (String)(It.next());
				if (ConnectionPool.getHandleToContainer(connName) != null) {
					if(cleanUpResources(connName,false))
						strBuf.append(connName + " is - Closed. ");
					else
						strBuf.append(connName + " could not be - Closed. ");
				}
			}
		} catch (Exception e) {
			logger.error(" closeConnections()  Exception ",e);
		}
		logger.info(strBuf.toString());
	}
	
	/**
	 * cleanUpResources(String) : Cleans the resources and remove connection from {@link ConnectionPool}
	 * @param connName : The connection for which clean-up will be done.
	 * @return Returns true in case success else returs false
	 */
	public static boolean cleanUpResources(String connName, boolean reconnectFlag){
		ConcurrentHashMap<String, ServerContainer> ATMSWITCH_CONN_MAP = ConnectionPool.getConnectionMap();
		ServerContainer containerHandler = ATMSWITCH_CONN_MAP.get(connName);
		try {
			if(containerHandler !=null){
				containerHandler.cleanResource(reconnectFlag);
			}else{
				logger.error("( "+connName+" ,cleanUpResources(,) ) No Processor found for this message....");
				return false;
			}
			ConnectionPool.removeConnection(connName);
			return true;
		}catch (Exception e) {
				logger.error(" ( " + connName + " ,cleanUpResources() ) Exception ",e);
				if(containerHandler !=null)
					containerHandler.closeResource();
				return false;
		}finally{
			containerHandler = null;
		}
	}
}
