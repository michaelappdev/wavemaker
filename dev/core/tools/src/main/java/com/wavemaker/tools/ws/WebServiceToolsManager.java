/*
 *  Copyright (C) 2007-2011 VMWare, Inc. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.wavemaker.tools.ws;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.activation.DataSource;
import javax.xml.bind.JAXBException;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.apache.ws.commons.schema.XmlSchema;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.XmlSchemaObjectTable;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.wavemaker.common.CommonConstants;
import com.wavemaker.common.WMRuntimeException;
import com.wavemaker.common.util.CastUtils;
import com.wavemaker.common.util.IOUtils;
import com.wavemaker.runtime.RuntimeAccess;
import com.wavemaker.runtime.server.DownloadResponse;
import com.wavemaker.runtime.server.ServerConstants;
import com.wavemaker.runtime.ws.BindingProperties;
import com.wavemaker.runtime.ws.HTTPBindingSupport;
import com.wavemaker.runtime.ws.HTTPBindingSupport.HTTPRequestMethod;
import com.wavemaker.runtime.ws.WebServiceException;
import com.wavemaker.runtime.ws.util.Constants;
import com.wavemaker.tools.common.ConfigurationException;
import com.wavemaker.tools.project.DeploymentManager;
import com.wavemaker.tools.project.ProjectManager;
import com.wavemaker.tools.pws.IPwsRestWsdlGenerator;
import com.wavemaker.tools.pws.PwsRestWsdlGeneratorBeanFactory;
import com.wavemaker.tools.service.DesignServiceManager;
import com.wavemaker.tools.service.definitions.Service;
import com.wavemaker.tools.ws.wadl.Wadl2Wsdl;
import com.wavemaker.tools.ws.wsdl.WSDL;
import com.wavemaker.tools.ws.wsdl.WSDLException;
import com.wavemaker.tools.ws.wsdl.WSDLManager;
import com.wavemaker.tools.ws.wsdl.WSDLUtils;

/**
 * Provides Web service related tooling APIs.
 * 
 * @author ffu
 * @author Jeremy Grelle
 * 
 */
public class WebServiceToolsManager {

	static final Logger logger = Logger.getLogger(WebServiceToolsManager.class);

	private static final String SERVICE_ID_ALREADY_EXISTS = "$already_exists$";

	private static final String XML_SCHEMA_TEXT_SEPERATOR = "\n--- schema end ---\n";

	private final ProjectManager projectMgr;

	private final DesignServiceManager designServiceMgr;

	public WebServiceToolsManager(ProjectManager projectMgr,
			DesignServiceManager designServiceMgr) {
		this.projectMgr = projectMgr;
		this.designServiceMgr = designServiceMgr;
	}

	/**
	 * Imports the specified WSDL. This will create the service class files and
	 * register the service DesignServiceManager.
	 * 
	 * @param wsdlPath
	 *            The path to the WSDL.
	 * @param serviceId
	 *            The service ID for this WSDL. If this is null, a generated one
	 *            will be set in the WSDL.
	 * @param overwrite
	 *            true to overwrite the service with the same service ID; false
	 *            to simply return the string "$already_exists".
	 * @return The service ID, or the string "$already_exists$" if the service
	 *         ID already exists and overwrite is false.
	 * @throws WSDLException
	 * @throws IOException
	 * @throws JAXBException
	 */
	public String importWSDL(String wsdlPath,
			String serviceId, // salesforce
			boolean overwrite, String username, String password,
			String partnerName) throws WSDLException, IOException,
			JAXBException, ParserConfigurationException, SAXException,
			TransformerException {

		logger.info("Importing " + wsdlPath);
		String srcPath;

		// boolean isLocal = true;
		// create temp wsdl file if the URI is a web address
		File tmpWsdlFile = null;
		if (wsdlPath.startsWith("http")) {
			// isLocal = false;
			WSDL tmpWsdl = WSDLManager.processWSDL(wsdlPath, serviceId);
			File tempDir = IOUtils.createTempDirectory();
			tmpWsdlFile = new File(tempDir, tmpWsdl.getServiceId()
					+ Constants.WSDL_EXT);
			WSDLUtils.writeDefinition(tmpWsdl.getDefinition(), tmpWsdlFile);
			modifyServiceName(tmpWsdlFile);
			srcPath = tmpWsdlFile.getCanonicalPath();
		} else {
			srcPath = wsdlPath;
		}

		WSDL origWsdl = null;
		File origWsdlFile = null;
		// if (isLocal) {
		origWsdlFile = new File(srcPath);
		if (origWsdlFile.isDirectory()) {
			String srvId = null;
			File[] listFiles = origWsdlFile.listFiles();
			for (File f : listFiles) {
				if (f.getName().toLowerCase().endsWith(Constants.WSDL_EXT)) {
					srvId = importWSDL(f.getCanonicalPath(), null, true,
							username, password, partnerName); // salesforce
				}
			}
			return srvId;
		}
		origWsdl = WSDLManager.processWSDL(origWsdlFile.toURI().toString(),
				serviceId);
		// } else {
		// origWsdl = WSDLManager.processWSDL(wsdlPath, serviceId);
		// }

		if (!overwrite
				&& designServiceMgr.serviceExists(origWsdl.getServiceId())) {
			return SERVICE_ID_ALREADY_EXISTS + origWsdl.getServiceId();
		}

		// create service runtime folder
		File runtimeDir = designServiceMgr.getServiceRuntimeDirectory(
				origWsdl.getServiceId()).getFile();
		if (!runtimeDir.exists()) {
			runtimeDir.mkdirs();
		}

		// create package folder under service runtime folder. This is the
		// place where we put the wsdl file and the service spring file.
		File packageDir = CodeGenUtils.getPackageDir(runtimeDir,
				origWsdl.getPackageName());
		if (!packageDir.exists()) {
			packageDir.mkdirs();
		}

		File wsdlFile;
		String wsdlUri = null;
		// if (isLocal) {
		// copy user-specified WSDL file to the package folder
		wsdlFile = new File(packageDir, origWsdlFile.getName());
		// wsdlUri = wsdlFile.toURI().toString();
		if (!wsdlFile.getCanonicalFile()
				.equals(origWsdlFile.getCanonicalFile())) {
			IOUtils.copy(origWsdlFile, wsdlFile);
		}
		// also copy xsd file(s) used by the WSDL
		Map<String, Element> schemas = origWsdl.getSchemas();
		if (schemas != null) {
			for (String systemId : schemas.keySet()) {
				File xsdFile = getLocalXsdFileFromSystemId(systemId);
				if (xsdFile != null && xsdFile.exists()) {
					IOUtils.copy(xsdFile,
							new File(packageDir, xsdFile.getName()));
				}
			}
		}
		// } else {
		// //wsdlUri = wsdlPath;
		// wsdlFile = new File(packageDir, origWsdl.getServiceId() +
		// Constants.WSDL_EXT);
		// WSDLUtils.writeDefinition(origWsdl.getDefinition(), wsdlFile);
		// }

		// modifyServiceName(wsdlFile);

		wsdlUri = wsdlFile.toURI().toString();

		// do the import which would generate service Java files and resource
		// files
		ImportWS importWS = new ImportWS();
		importWS.setWsdlUri(wsdlUri);
		importWS.setServiceId(serviceId);
		importWS.setDestdir(new FileSystemResource(runtimeDir));
		importWS.setPartnerName(partnerName);
		WSDL wsdl = importWS.generateServiceClass();
		wsdl.setPartnerName(partnerName);

		// update DesignServiceManager with the WSDL that contains the
		// type (TypeMapper) information.
		designServiceMgr.defineService(wsdl, username, password); // salesforce

		String srvId = wsdl.getServiceId();
		logger.info("Import successful: " + srvId);

		if (srvId.equals(CommonConstants.SALESFORCE_SERVICE)) { // salesforce
			DeploymentManager deploymentManager = (DeploymentManager) RuntimeAccess
					.getInstance().getSpringBean("deploymentManager");
			deploymentManager.testRunStart();
		}

		return srvId;
	}

	/**
	 * Imports the specified WADL. This will create the service class files and
	 * register the service DesignServiceManager.
	 * 
	 * @param wadlPath
	 *            The path the the WADL file.
	 * @param serviceId
	 *            The service ID for this WSDL. If this is null, a generated one
	 *            will be set in the WSDL.
	 * @param overwrite
	 *            true to overwrite the service with the same service ID; false
	 *            to simply return the string "$already_exists".
	 * @return The service ID, or the string "$already_exists$" if the service
	 *         ID already exists and overwrite is false.
	 * @throws WSDLException
	 * @throws IOException
	 * @throws JAXBException
	 */
	public String importWADL(String wadlPath, String serviceId,
			boolean overwrite) throws WSDLException, IOException,
			JAXBException, ParserConfigurationException, SAXException,
			TransformerException {
		File tempDir = IOUtils.createTempDirectory();
		try {
			File wsdlFile = generateWsdlFromWadl(wadlPath, tempDir);
			return importWSDL(wsdlFile.getCanonicalPath(), serviceId,
					overwrite, null, null, null);
		} finally {
			IOUtils.deleteRecursive(tempDir);
		}
	}

	/**
	 * Imports the specified uploaded file from the request.
	 * 
	 * @param file
	 *            The uploaded file.
	 * @param serviceId
	 *            The service ID for this WSDL. If this is null, a generated one
	 *            will be set in the WSDL.
	 * @param overwrite
	 *            true to overwrite the service with the same service ID; false
	 *            to simply return the string "$already_exists".
	 * @return The service ID, or the string "$already_exists$" if the service
	 *         ID already exists and overwrite is false.
	 * @throws IOException
	 * @throws WSDLException
	 * @throws JAXBException
	 */
	public String importUploadedFile(MultipartFile file, String serviceId,
			String overwrite, String username, String password)
			throws IOException, WSDLException, JAXBException {
		File tempDir = IOUtils.createTempDirectory();
		String fileName = file.getOriginalFilename();
		boolean isWSDL = true;
		if (fileName != null && fileName.length() > 0) {
			File f = new File(fileName);
			fileName = f.getName();
			// assuming all WADL files have file extension .wadl
			if (fileName.toLowerCase().endsWith(".wadl")) {
				isWSDL = false;
			}
		} else {
			// this should never been called since the uploaded file should
			// always has a file name associate with it.
			fileName = "temp.wsdl";
		}
		try {
			File wsdlFile = new File(tempDir, fileName);
			file.transferTo(wsdlFile);
			modifyServiceName(wsdlFile);
			if (isWSDL) {
				return importWSDL(wsdlFile.getCanonicalPath(), serviceId,
						Boolean.valueOf(overwrite), username, password, null);
			} else {
				return importWADL(wsdlFile.getCanonicalPath(), serviceId,
						Boolean.valueOf(overwrite));
			}
		} catch (Exception ex) {
			throw new WSDLException(ex);
		} finally {
			IOUtils.deleteRecursive(tempDir);
		}
	}

	/**
	 * Adds Feed Service to the system.
	 * 
	 * @return The service ID.
	 * @throws IOException
	 * @throws JAXBException
	 */
	public String registerFeedService() throws JAXBException, IOException {
		FeedServiceDefinition definition = new FeedServiceDefinition();
		String serviceId = definition.getServiceId();

		designServiceMgr.validateServiceId(serviceId);

		designServiceMgr.defineService(definition, null, null);

		return serviceId;
	}

	public String buildRestService(String serviceName, String operationName,
			List<RESTInputParam> inputs, String parameterizedUrl,
			String method, String contentType, String outputType,
			String xmlSchemaText, String xmlSchemaPath, boolean overwrite)
			throws IOException, javax.wsdl.WSDLException, SAXException,
			ParserConfigurationException, WSDLException, JAXBException,
			TransformerException {
		List<String> operationName_list = new ArrayList<String>();
		operationName_list.add(operationName);
		List<List<RESTInputParam>> inputParms_list = new ArrayList<List<RESTInputParam>>();
		inputParms_list.add(inputs);

		String result = buildRestService(serviceName, operationName_list,
				inputParms_list, parameterizedUrl, method, contentType,
				outputType, xmlSchemaText, xmlSchemaPath, overwrite);

		return result;
	}

	public String buildRestService(String serviceName,
			List<String> operationName_list,
			List<List<RESTInputParam>> inputs_list, String parameterizedUrl,
			String method, String contentType, String outputType,
			String xmlSchemaText, String xmlSchemaPath, boolean overwrite)
			throws IOException, javax.wsdl.WSDLException, SAXException,
			ParserConfigurationException, WSDLException, JAXBException,
			TransformerException {

		String result = buildRestService(serviceName, operationName_list,
				inputs_list, parameterizedUrl, method, contentType, outputType,
				xmlSchemaText, xmlSchemaPath, overwrite, null);

		return result;
	}

	/**
	 * Builds a REST WSDL and imports it.
	 * 
	 * @param serviceName
	 *            The name of this service.
	 * @param operationName_list
	 *            The list of operation names
	 * @param inputs_list
	 *            The list of input parameters (per operations).
	 * @param parameterizedUrl
	 *            The parameterized service URLs (per operations).
	 * @param method
	 *            The HTTP method, valid values are "GET" and "POST".
	 * @param contentType
	 *            The content type, examples include "text/xml" and
	 *            "application/json-rpc".
	 * @param outputType
	 *            The XML qualified name of the ouput type.
	 * @param xmlSchemaText
	 *            The XML schema or multipe XML schemas using
	 *            <code>XML_SCHEMA_TEXT_SEPERATOR</code>
	 * @param xmlSchemaPath
	 *            The path (either URL or file) to the XML schema.
	 * @param overwrite
	 *            true to overwrite the service with the same service ID; false
	 *            to simply return the string "$already_exists".
	 * @param partnerName
	 *            The partner name
	 * @return The service ID, or the string "$already_exists$" if the service
	 *         ID already exists and overwrite is false.
	 * @throws IOException
	 * @throws javax.wsdl.WSDLException
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws WSDLException
	 * @throws JAXBException
	 * @throws TransformerException
	 */
	public String buildRestService(String serviceName,
			List<String> operationName_list,
			List<List<RESTInputParam>> inputs_list, String parameterizedUrl,
			String method, String contentType, String outputType,
			String xmlSchemaText, String xmlSchemaPath, boolean overwrite,
			String partnerName) throws IOException, javax.wsdl.WSDLException,
			SAXException, ParserConfigurationException, WSDLException,
			JAXBException, TransformerException {

		int operCnt = operationName_list.size();

		IPwsRestWsdlGenerator restWsdlGenerator;

		if (partnerName == null || partnerName.length() == 0) {
			restWsdlGenerator = new RESTWsdlGenerator(serviceName,
					constructNamespace(parameterizedUrl), operationName_list,
					parameterizedUrl);
		} else {
			PwsRestWsdlGeneratorBeanFactory factory = (PwsRestWsdlGeneratorBeanFactory) RuntimeAccess
					.getInstance().getSpringBean(
							"pwsRestWsdlGeneratorBeanFactory");
			restWsdlGenerator = factory.getPwsRestWsdlGenerator(partnerName);
			restWsdlGenerator.setServiceName(serviceName);
			restWsdlGenerator
					.setNamespace(constructNamespace(parameterizedUrl));
			restWsdlGenerator.setOperationNameList(operationName_list);
			restWsdlGenerator.setParameterizedUrl(parameterizedUrl);
		}
		restWsdlGenerator.setHttpMethod(method);
		restWsdlGenerator.setContentType(contentType);

		// If you build a REST service from sample call, the size of inputs is
		// always zero.
		// In this case, let's create an input parameter on the fly. We are
		// assuming that POST method always
		// requires a parameter to be passed because there is no reason to
		// create a POST method service if no
		// input arguments need to be passed when calling the service.
		// if (method.equals("POST") && inputs.size() == 0) {
		// RESTInputParam parm = new RESTInputParam("postData", "string");
		// inputs.add(parm);
		// }

		// if (method.equals("POST") && (inputs_list == null ||
		// inputs_list.size() == 0)) {
		if (method.equals("POST")) {
			// inputs_list = new ArrayList<List<RESTInputParam>>();
			for (int i = 0; i < operCnt; i++) {
				List<RESTInputParam> inputs = inputs_list.get(i);
				if (inputs == null || inputs.size() == 0) {
					// inputs = new ArrayList<RESTInputParam>();
					RESTInputParam parm = new RESTInputParam("postData",
							"string");
					inputs.add(parm);
				}
			}
		}

		restWsdlGenerator.setInputParts_list(inputs_list);
		if (outputType != null) {
			if (outputType.equals("string")) {
				restWsdlGenerator.setStringOutput(true);
			} else {
				restWsdlGenerator.setOutputType(outputType);
				/*
				 * int i = outputType.lastIndexOf(':'); QName outType = null; if
				 * (i > -1) { outType = new QName(outputType.substring(0, i),
				 * outputType.substring(i + 1)); } else { outType = new
				 * QName(outputType); }
				 * restWsdlGenerator.setOutputElementType(outType);
				 */
			}
		}

		if (xmlSchemaText != null && xmlSchemaText.length() > 0) {
			List<String> schemaStrings = seperateXmlSchemaText(xmlSchemaText);
			restWsdlGenerator.setSchemaStrings(schemaStrings);
		}
		if (xmlSchemaPath != null && xmlSchemaPath.length() > 0) {
			XmlSchema xmlSchema = constructXmlSchema(xmlSchemaPath);
			List<Element> schemaElements = new ArrayList<Element>();
			for (Document schemaDocument : xmlSchema.getAllSchemas()) {
				schemaElements.add(schemaDocument.getDocumentElement());
			}
			restWsdlGenerator.setSchemaElements(schemaElements);
		}
		File tempDir = IOUtils.createTempDirectory();
		try {
			File wsdlFile = new File(tempDir, serviceName + Constants.WSDL_EXT);
			restWsdlGenerator.write(wsdlFile);
			return importWSDL(wsdlFile.getCanonicalPath(), null, overwrite,
					null, null, partnerName); // salesforce
		} finally {
			IOUtils.deleteRecursive(tempDir);
		}
	}

	/**
	 * Returns a list of XML qualified names of the elements defined in the
	 * schema(s).
	 * 
	 * @param xmlSchemaPath
	 *            The path the the XML schema.
	 * @param xmlSchemaText
	 *            Represents a XML schema or multipe XML schemas using
	 *            <code>XML_SCHEMA_TEXT_SEPERATOR</code>
	 * @return A list of XML qualified names of the elements.
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	public List<String> getSchemaElementTypes(String xmlSchemaPath,
			String xmlSchemaText) throws IOException,
			ParserConfigurationException, SAXException {
		if (xmlSchemaPath != null && xmlSchemaPath.length() > 0) {
			XmlSchema xmlSchema = constructXmlSchema(xmlSchemaPath);
			return getAllSchemaElements(xmlSchema);
		} else if (xmlSchemaText != null && xmlSchemaText.length() > 0) {
			List<String> schemaStrings = seperateXmlSchemaText(xmlSchemaText);
			List<String> allSchemaElements = new ArrayList<String>();
			for (String schemaString : schemaStrings) {
				DocumentBuilderFactory dbf = DocumentBuilderFactory
						.newInstance();
				dbf.setNamespaceAware(true);
				DocumentBuilder db = dbf.newDocumentBuilder();
				Reader reader = new StringReader(schemaString);
				Document doc = db.parse(new InputSource(reader));
				XmlSchemaCollection xmlSchemaColl = new XmlSchemaCollection();
				XmlSchema xmlSchema = xmlSchemaColl.read(doc, null);
				allSchemaElements.addAll(getAllSchemaElements(xmlSchema));
			}
			return allSchemaElements;
		}
		return Collections.emptyList();
	}

	/**
	 * Given the XML instance, generate the corresponding XML schema(s). For a
	 * single schema, this will just return the schema in string format. If
	 * multiple schemas, then this will return a string containing all schemas
	 * seperated by <code>XML_SCHEMA_TEXT_SEPERATOR</code>.
	 * 
	 * @param xml
	 *            The XML instance.
	 * @return XML schema text which could be a single schema or multiple
	 *         schemas.
	 * @throws IOException
	 * @throws XmlException
	 */
	public String convertXmlToSchema(String xml) throws IOException,
			XmlException {
		List<String> schemaStrings = XsdGenerator.generate(xml);
		if (schemaStrings.size() == 1) {
			return schemaStrings.get(0);
		} else if (schemaStrings.size() > 1) {
			StringBuilder sb = new StringBuilder();
			for (String schemaString : schemaStrings) {
				sb.append(schemaString);
				sb.append(XML_SCHEMA_TEXT_SEPERATOR);
			}
			return sb.toString();
		} else {
			return null;
		}
	}

	public List<String> invokeRestCall(String endpointAddress) {
		return this.invokeRestCall(endpointAddress, "GET", null, null, false,
				null, null);
	}

	public List<String> invokeRestCall(String endpointAddress,
			boolean basicAuth, String userName, String password) {
		return this.invokeRestCall(endpointAddress, "GET", null, null,
				basicAuth, userName, password);
	}

	/**
	 * Invokes a HTTP GET for the given endpoint address.
	 * 
	 * @param endpointAddress
	 *            The service endpoint.
	 * @param method
	 *            Request method (GET, POST)
	 * @param contentType
	 *            Mime content type
	 * @param postData
	 *            post data
	 * @return A list of string with list[0] represents the service response and
	 *         list[1] represents the optional error message.
	 */
	public List<String> invokeRestCall(String endpointAddress, String method,
			String contentType, String postData, boolean basicAuth,
			String userName, String password) {
		QName serviceQName = new QName(constructNamespace(endpointAddress),
				"TestService");

		String responseString = null;
		String errorMessage = null;

		try {
			// TODO: For now, we only support the xml contenty type. It should
			// be extended to cover other content
			// TODO: types such as text/plain.
			String cType = contentType + "; charset=UTF-8";
			DataSource postSource = HTTPBindingSupport.createDataSource(cType,
					postData);

			BindingProperties bp = null;
			if (basicAuth) {
				bp = new BindingProperties();
				bp.setHttpBasicAuthUsername(userName);
				bp.setHttpBasicAuthPassword(password);
			}
			responseString = HTTPBindingSupport.getResponseString(serviceQName,
					serviceQName, endpointAddress,
					HTTPRequestMethod.valueOf(method), postSource, bp);
			try {
				// validate XML
				XmlObject.Factory.parse(responseString);
			} catch (XmlException e) {
				errorMessage = "The response is not in a valid XML format.";
			}
		} catch (Throwable e) {
			e.printStackTrace();
			errorMessage = e.toString();
		}

		List<String> rtn = new ArrayList<String>();
		rtn.add(responseString);
		if (errorMessage != null) {
			rtn.add(errorMessage);
		}
		return rtn;
	}

	public RESTWsdlSettings generateRESTWsdlSettings(String endpointAddress)
			throws WebServiceException, IOException, XmlException {
		return this
				.generateRESTWsdlSettings(endpointAddress, false, null, null);
	}

	public RESTWsdlSettings generateRESTWsdlSettings(String endpointAddress,
			boolean basicAuth, String userName, String password)
			throws WebServiceException, IOException, XmlException {
		return this.generateRESTWsdlSettings(endpointAddress, "GET", null,
				null, basicAuth, userName, password);
	}

	/**
	 * Generates the REST settings using the given request URL. This method will
	 * make a HTTP GET call using the request URL and by using the XML response,
	 * it will then try to generate the REST settings.
	 * 
	 * @param endpointAddress
	 *            The actual request URL.
	 * @param method
	 *            Request method (GET, POST)
	 * @param contentType
	 *            Mime content type
	 * @param postData
	 *            post data
	 * @return
	 * @throws WebServiceException
	 * @throws IOException
	 * @throws XmlException
	 */
	public RESTWsdlSettings generateRESTWsdlSettings(String endpointAddress,
			String method, String contentType, String postData,
			boolean basicAuth, String userName, String password)
			throws WebServiceException, IOException, XmlException {
		RESTWsdlSettings settings = new RESTWsdlSettings();

		URL serviceUrl = new URL(endpointAddress);

		serviceUrl.getHost();
		String serviceName = constructServiceName(serviceUrl);
		QName serviceQName = new QName(constructNamespace(endpointAddress),
				serviceName);

		// TODO: For now, we only support the xml contenty type. It should be
		// extended to cover other content
		// TODO: types such as text/plain.
		String cType = contentType + "; charset=UTF-8";
		DataSource postSource = HTTPBindingSupport.createDataSource(cType,
				postData);

		BindingProperties bp = null;
		if (basicAuth) {
			bp = new BindingProperties();
			bp.setHttpBasicAuthUsername(userName);
			bp.setHttpBasicAuthPassword(password);
		}

		String responseString = HTTPBindingSupport.getResponseString(
				serviceQName, serviceQName, endpointAddress,
				HTTPRequestMethod.valueOf(method), postSource, bp);

		String outputType = null;
		String xmlSchemaText = null;
		try {
			xmlSchemaText = convertXmlToSchema(responseString);
			outputType = getXmlRootElementType(responseString);
		} catch (Exception e) {
			// can't generate schema, so just set the output type to raw string
			outputType = "string";
		}

		String query = serviceUrl.getQuery();
		List<RESTInputParam> inputs = new ArrayList<RESTInputParam>();
		String parameterizedUrl = null;
		if (query != null) {
			StringBuilder sb = new StringBuilder();
			sb.append(getUrlOmitQuery(endpointAddress));
			sb.append("?");
			String[] qparts = query.split("&");
			for (String qpart : qparts) {
				int i = qpart.indexOf('=');
				if (i > -1) {
					String name = qpart.substring(0, i);
					sb.append(name);
					sb.append("={");
					sb.append(name);
					sb.append("}&");
					inputs.add(new RESTInputParam(name, "string"));
				} else {
					sb.append(qpart);
					sb.append("&");
				}
			}
			if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '&') {
				sb.deleteCharAt(sb.length() - 1);
			}
			parameterizedUrl = sb.toString();
		} else {
			parameterizedUrl = endpointAddress;
		}

		settings.setServiceName(serviceName);
		settings.setOperationName("invoke");
		settings.setInputs(inputs);
		settings.setParameterizedUrl(parameterizedUrl);
		settings.setOutputType(outputType);
		settings.setXmlSchemaText(xmlSchemaText);

		return settings;
	}

	public String getWSDL(String serviceId) throws IOException {
		File file = getWSDLFile(serviceId);
		if (file != null) {
			if (file.length() < 1000000) {
				return FileUtils.readFileToString(file,
						ServerConstants.DEFAULT_ENCODING);
			} else {
				return "services/webService.download?method=downloadWSDL&serviceId="
						+ serviceId;
			}
		}
		return null;
	}

	public File getWSDLFile(String serviceId) {
		Service service = designServiceMgr.getService(serviceId);
		if (service != null) {
			File serviceRuntimeDirectory;
			try {
				serviceRuntimeDirectory = designServiceMgr
						.getServiceRuntimeDirectory(serviceId).getFile();
			} catch (IOException ex) {
				throw new WMRuntimeException(ex);
			}
			String clazz = service.getClazz();
			String packagePath = clazz.replace('.', '/');
			packagePath = packagePath
					.substring(0, packagePath.lastIndexOf('/'));
			File packageDir = new File(serviceRuntimeDirectory, packagePath);
			if (packageDir.exists()) {
				File[] files = packageDir.listFiles();
				if (files != null) {
					for (File file : files) {
						if (file.getName().toLowerCase()
								.endsWith(Constants.WSDL_EXT)) {
							return file;
						}
					}
				}
			}
		}
		return null;
	}

	public DownloadResponse downloadWSDL(String serviceId) throws IOException {
		DownloadResponse response = new DownloadResponse();
		File wsdlFile = getWSDLFile(serviceId);
		FileInputStream fis = new FileInputStream(wsdlFile);
		response.setContents(fis);
		response.setFileName(wsdlFile.getName());
		return response;
	}

	/**
	 * Given the systemId which could be of format like
	 * "http://www.abc.com/test.xsd" or "file:/C:/temp/test.xsd". If the
	 * systemId represents a local XSD file (e.g. file:C:/temp/test.xsd) then
	 * returns it; otherwise returns null.
	 * 
	 * @param systemId
	 * @return An XSD file.
	 */
	private static File getLocalXsdFileFromSystemId(String systemId) {
		if (!systemId.endsWith(Constants.XSD_EXT)) {
			return null;
		}
		URL url = null;
		try {
			url = new URL(systemId);
		} catch (MalformedURLException e) {
			return null;
		}
		if (url.getProtocol().equals("file") && url.getFile() != null) {
			return new File(url.getFile());
		} else {
			return null;
		}
	}

	private static File generateWsdlFromWadl(String wadlPath, File outDir) {
		String wadlUri = null;
		if (wadlPath.startsWith("http")) {
			wadlUri = wadlPath;
		} else {
			File file = new File(wadlPath);
			wadlUri = file.toURI().toString();
		}
		try {
			Wadl2Wsdl wadl2Wsdl = new Wadl2Wsdl(new URI(wadlUri));
			return wadl2Wsdl.generateWSDL(outDir);
		} catch (Exception e) {
			throw new ConfigurationException(e);
		}
	}

	/**
	 * This method returns each individual XML schema in the given schemaText.
	 * schemaText represents an XML schema or mulitple XML schemas using <code>
	 * XML_SCHEMA_TEXT_SEPERATOR</code>.
	 */
	public static List<String> seperateXmlSchemaText(String schemaText) {
		if (schemaText == null) {
			return Collections.emptyList();
		}
		String[] schemaStrings = schemaText.split(XML_SCHEMA_TEXT_SEPERATOR);
		List<String> schemas = new ArrayList<String>();
		for (String schemaString : schemaStrings) {
			if (schemaString.length() > 0) {
				schemas.add(schemaString);
			}
		}
		return schemas;
	}

	private static String getUrlOmitQuery(String url) {
		int index = url.indexOf('?');
		String namespace = null;
		if (index > -1) {
			namespace = url.substring(0, index);
		} else {
			namespace = url;
		}
		return namespace;
	}

	private static String constructNamespace(String url) {
		String namespace = getUrlOmitQuery(url);
		namespace = namespace.replace('{', 'x');
		namespace = namespace.replace('}', 'x');
		return namespace;
	}

	/**
	 * Constructs a service name for the given URL.
	 * 
	 * @param url
	 * @return
	 */
	private static String constructServiceName(URL url) {
		String host = url.getHost();
		int i = host.indexOf('.');
		if (i > -1) {
			String s1 = host.substring(i + 1, host.length());
			int j = s1.indexOf('.');
			String s2 = null;
			if (j > -1) {
				s2 = s1.substring(0, j);
			} else {
				s2 = host.substring(0, i);
			}
			return s2;
		}
		return host;
	}

	/**
	 * Constructs an <code>XmlSchema</code> object for the given schema path.
	 */
	public static XmlSchema constructXmlSchema(String schemaPath)
			throws IOException {
		URL url = null;
		if (schemaPath.startsWith("http")) {
			url = new URL(schemaPath);
		} else {
			File f = new File(schemaPath);
			url = f.toURL();
		}
		InputSource input = new InputSource(url.openStream());
		XmlSchemaCollection xmlSchemaColl = new XmlSchemaCollection();
		XmlSchema xmlSchema = xmlSchemaColl.read(input, null);
		return xmlSchema;
	}

	/**
	 * Returns the XML qualified name of the root element type for the given
	 * XML.
	 */
	public static String getXmlRootElementType(String xml)
			throws ParserConfigurationException, SAXException, IOException {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		DocumentBuilder db = dbf.newDocumentBuilder();
		Reader reader = new StringReader(xml);
		Document doc = db.parse(new InputSource(reader));
		Node node = doc.getFirstChild();
		if (node != null) {
			String namespaceURI = node.getNamespaceURI();
			String localName = node.getLocalName();
			if (localName != null) {
				return namespaceURI == null ? localName : namespaceURI + ":"
						+ localName;
			}
		}
		return null;
	}

	/**
	 * Returns a list of XML qualified names of the elements defined in the
	 * schema.
	 */
	private static List<String> getAllSchemaElements(XmlSchema xmlSchema) {
		List<String> types = new ArrayList<String>();
		XmlSchemaObjectTable elements = xmlSchema.getElements();
		Iterator<QName> iter = CastUtils.cast(elements.getNames());
		while (iter.hasNext()) {
			QName next = iter.next();
			String namespaceURI = next.getNamespaceURI();
			types.add(((namespaceURI == null || namespaceURI.length() == 0) ? ""
					: namespaceURI + ":")
					+ next.getLocalPart());
		}
		return types;
	}

	public BindingProperties getBindingProperties(String serviceId)
			throws JAXBException, IOException {
		return WebServiceSpringSupport.getBindingProperties(
				projectMgr.getCurrentProject(), designServiceMgr, serviceId);
	}

	public void setBindingProperties(String serviceId,
			BindingProperties bindingProperties) throws JAXBException,
			IOException {
		WebServiceSpringSupport.setBindingProperties(
				projectMgr.getCurrentProject(), designServiceMgr, serviceId,
				bindingProperties);
	}

	// If the service name happens to be the same as one of the operation names,
	// append "Svc" at the
	// end of the service name to prevent the operation Java source file from
	// being overwritten
	// by the service Java source file.
	private void modifyServiceName(File wsdlf) throws IOException,
			ParserConfigurationException, SAXException, TransformerException {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		DocumentBuilder docBuilder = dbf.newDocumentBuilder();
		Document doc = docBuilder.parse(wsdlf);
		NodeList nlistOp = doc.getElementsByTagNameNS("*", "operation");
		NodeList nlistSvc = doc.getElementsByTagNameNS("*", "service");

		if (nlistOp == null || nlistOp.getLength() == 0 || nlistSvc == null
				|| nlistSvc.getLength() == 0) {
			return;
		}

		String[] opList = new String[nlistOp.getLength()];
		for (int i = 0; i < nlistOp.getLength(); i++) {
			Node nodeOp = nlistOp.item(i);
			NamedNodeMap nMap = nodeOp.getAttributes();
			for (int j = 0; j < nMap.getLength(); j++) {
				Node attr = nMap.item(j);
				if (attr.getNodeName().equals("name")) {
					opList[i] = attr.getNodeValue();
					break;
				}
			}
		}

		String svcName = null;

		Node nodeSvc = nlistSvc.item(0); // assumes there is only once service
											// name defined in a WSDL
		Node svcNameAttr = null;
		NamedNodeMap nMap = nodeSvc.getAttributes();
		nMap.getLength();
		for (int j = 0; j < nMap.getLength(); j++) {
			svcNameAttr = nMap.item(j);
			if (svcNameAttr.getNodeName().equals("name")) {
				svcName = svcNameAttr.getNodeValue();
				break;
			}
		}

		if (opList.length == 0 || svcName == null) {
			return;
		}

		boolean sameName = false;
		for (String opName : opList) {
			if (opName != null && opName.equals(svcName)) {
				sameName = true;
				break;
			}
		}

		if (!sameName) {
			return;
		}

		svcNameAttr.setNodeValue(svcName + "Svc");

		TransformerFactory tFactory = TransformerFactory.newInstance();
		Transformer tFormer = tFactory.newTransformer();

		Source source = new DOMSource(doc);
		Result dest = new StreamResult(wsdlf);
		tFormer.transform(source, dest);
	}
}