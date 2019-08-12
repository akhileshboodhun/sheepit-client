/*
 * Copyright (C) 2010-2014 Laurent CLOUET
 * Author Laurent CLOUET <laurent.clouet@nopnop.net>
 *
 * This program is free software; you can redistribute it and/or 
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.sheepit.client;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.sheepit.client.server.API;
import com.sheepit.client.server.datamodel.HeartBeatInfos;
import com.sheepit.client.server.datamodel.JobInfos;
import com.sheepit.client.server.datamodel.ServerConfig;
import com.sheepit.client.server.datamodel.RequestEndPoint;
import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import org.simpleframework.xml.convert.AnnotationStrategy;
import org.simpleframework.xml.core.Persister;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.sheepit.client.Configuration.ComputeType;
import com.sheepit.client.Error.ServerCode;
import com.sheepit.client.exception.FermeException;
import com.sheepit.client.exception.FermeExceptionBadResponseFromServer;
import com.sheepit.client.exception.FermeExceptionNoRendererAvailable;
import com.sheepit.client.exception.FermeExceptionNoRightToRender;
import com.sheepit.client.exception.FermeExceptionNoSession;
import com.sheepit.client.exception.FermeExceptionNoSpaceLeftOnDevice;
import com.sheepit.client.exception.FermeExceptionServerInMaintenance;
import com.sheepit.client.exception.FermeExceptionServerOverloaded;
import com.sheepit.client.exception.FermeExceptionSessionDisabled;
import com.sheepit.client.exception.FermeServerDown;
import com.sheepit.client.os.OS;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.simplexml.SimpleXmlConverterFactory;

public class Server extends Thread implements HostnameVerifier, X509TrustManager {
	private API service;
	private ServerConfig serverConfig;
	private Configuration user_config;
	private Client client;
	private Log log;
	private long lastRequestTime;
	private int keepmealive_duration; // time in ms
	
	public Server(String url_, Configuration user_config_, Client client_) {
		super();
		this.user_config = user_config_;
		this.client = client_;
		this.log = Log.getInstance(this.user_config);
		this.lastRequestTime = 0;
		this.keepmealive_duration = 15 * 60 * 1000; // default 15min


		CookieManager cookieManager = new CookieManager();
		cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
		OkHttpClient.Builder builder = new OkHttpClient.Builder();
		builder.cookieJar(new JavaNetCookieJar(cookieManager));
		
		Retrofit retrofit = new Retrofit.Builder()
				.baseUrl(url_)
				.client(builder.build())
				.addConverterFactory(SimpleXmlConverterFactory.createNonStrict(new Persister(new AnnotationStrategy())))
				.build();

		service = retrofit.create(API.class);
	}
	
	public void run() {
		this.stayAlive();
	}
	
	public void stayAlive() {
		while (true) {
			long current_time = new Date().getTime();
			if ((current_time - this.lastRequestTime) > this.keepmealive_duration) {
				try {
					int rendertime = 0;
					int remainingtime = 0;
					if (this.client.getRenderingJob().getProcessRender() != null) {
						rendertime = this.client.getRenderingJob().getProcessRender().getDuration();
						remainingtime = this.client.getRenderingJob().getProcessRender().getRemainingDuration();
					}
					Response<HeartBeatInfos> response = service.heartBeat(serverConfig.getRequestEndPoint("keepmealive").getPath(),
							this.client.getRenderingJob().getFrameNumber(),
							this.client.getRenderingJob().getId(),
							this.client.getRenderingJob().getExtras(),
							rendertime,
							remainingtime).execute();

					if (response.isSuccessful() == false) {
						// do nothing, it's simply an hearbeat
					}
					else {
						this.lastRequestTime = new Date().getTime();

						HeartBeatInfos heartBeatInfos = response.body();
						if (ServerCode.fromInt(heartBeatInfos.getStatus()) == ServerCode.KEEPMEALIVE_STOP_RENDERING) {
							this.log.debug("Server::stayAlive server asked to kill local render process");
							// kill the current process, it will generate an error but it's okay
							if (this.client != null && this.client.getRenderingJob() != null && this.client.getRenderingJob().getProcessRender().getProcess() != null) {
								this.client.getRenderingJob().setServerBlockJob(true);
								this.client.getRenderingJob().setAskForRendererKill(true);
								OS.getOS().kill(this.client.getRenderingJob().getProcessRender().getProcess());
							}
						}
					}
				}
				catch (IOException e) {
					StringWriter sw = new StringWriter();
					PrintWriter pw = new PrintWriter(sw);
					e.printStackTrace(pw);
					this.log.debug("Server::stayAlive IOException " + e + " stacktrace: " + sw.toString());
				}
			}
			try {
				Thread.sleep(60 * 1000); // 1min
			}
			catch (InterruptedException e) {
				return;
			}
			catch (Exception e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				this.log.debug("Server::stayAlive Exception " + e + " stacktrace: " + sw.toString());
			}
		}
	}
	
	public Error.Type getConfiguration() {
		OS os = OS.getOS();

		try {
			// this.log.debug("Server::getConfiguration url " + url_remote);
			Response<ServerConfig> request = service.newSession(
					this.user_config.getLogin(),
					this.user_config.getPassword(),
					os.getCPU().family(),
					os.getCPU().model(),
					os.getCPU().name(),
					this.user_config.getNbCores() == -1 ? os.getCPU().cores() : this.user_config.getNbCores(),
					os.name(),
					os.getMemory(),
					os.getCPU().arch(),
					this.user_config.getJarVersion(),
					this.user_config.getHostname(),
					this.client.getGui().getClass().getSimpleName(),
					this.user_config.getExtras()
			).execute();


//			System.out.println("request " + request);

			if (request.isSuccessful() == false) {
				if (request.code() != HttpURLConnection.HTTP_OK  /* || newConfig.headers().get("contentType) .startsWith("text/html") */) {
					return Error.Type.ERROR_BAD_RESPONSE;
				}
				
				//if (request.errorBody().contentType())
			}

			serverConfig = request.body();
//			System.out.println("serverConfig " + serverConfig);

			if (ServerCode.fromInt(serverConfig.getStatus()) != ServerCode.OK) {
				return Error.ServerCodeToType(ServerCode.fromInt(serverConfig.getStatus()));
			}

			RequestEndPoint keepmealiveEndPoint = serverConfig.getRequestEndPoint("keepmealive");
			if (keepmealiveEndPoint != null && keepmealiveEndPoint.getMaxPeriod() > 0) {
				this.keepmealive_duration = (keepmealiveEndPoint.getMaxPeriod() - 120) * 1000; // put 2min of safety net
			}

			// ServerConfig(
			//			status=0,
			//			requestEndPoints=[
			//				RequestEndPoint(type=validate-job, path=/server/send_frame.php, maxPeriod=0),
			//				RequestEndPoint(type=request-job, path=/server/request_job.php, maxPeriod=0),
			//				RequestEndPoint(type=download-archive, path=/server/archive.php, maxPeriod=0),
			//				RequestEndPoint(type=error, path=/server/error.php, maxPeriod=0),
			//				RequestEndPoint(type=keepmealive, path=/server/keepmealive.php, maxPeriod=1440),
			//				RequestEndPoint(type=logout, path=/account.php?mode=logout&worker=1, maxPeriod=0),
			//				RequestEndPoint(type=last-render-frame, path=/ajax.php?action=webclient_get_last_render_frame_ui&type=raw, maxPeriod=0)])


			//				ServerCode ret = Utils.statusIsOK(document, "config");
			//				if (ret != ServerCode.OK) {
			//					return Error.ServerCodeToType(ret);
			//				}

//			if (r == HttpURLConnection.HTTP_OK && contentType.startsWith("text/xml")) {
//				DataInputStream in = new DataInputStream(connection.getInputStream());
//				Document document = null;
//
//				try {
//					document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
//				}
//				catch (SAXException e) {
//					this.log.error("getConfiguration error: failed to parse XML SAXException " + e);
//					return Error.Type.WRONG_CONFIGURATION;
//				}
//				catch (IOException e) {
//					this.log.error("getConfiguration error: failed to parse XML IOException " + e);
//					return Error.Type.WRONG_CONFIGURATION;
//				}
//				catch (ParserConfigurationException e) {
//					this.log.error("getConfiguration error: failed to parse XML ParserConfigurationException " + e);
//					return Error.Type.WRONG_CONFIGURATION;
//				}
//
//				ServerCode ret = Utils.statusIsOK(document, "config");
//				if (ret != ServerCode.OK) {
//					return Error.ServerCodeToType(ret);
//				}
//
//				Element config_node = null;
//				NodeList ns = null;
//				ns = document.getElementsByTagName("config");
//				if (ns.getLength() == 0) {
//					this.log.error("getConfiguration error: failed to parse XML, no node 'config'");
//					return Error.Type.WRONG_CONFIGURATION;
//				}
//				config_node = (Element) ns.item(0);
//
//				ns = config_node.getElementsByTagName("request");
//				if (ns.getLength() == 0) {
//					this.log.error("getConfiguration error: failed to parse XML, node 'config' has no child node 'request'");
//					return Error.Type.WRONG_CONFIGURATION;
//				}

//
//			}
//			else if (r == HttpURLConnection.HTTP_OK && contentType.startsWith("text/html")) {
//				return Error.Type.ERROR_BAD_RESPONSE;
//			}
//			else {
//				this.log.error("Server::getConfiguration: Invalid response " + contentType + " " + r);
//				return Error.Type.WRONG_CONFIGURATION;
//			}
		}
//		catch (ConnectException e) {
//			this.log.error("Server::getConfiguration error ConnectException " + e);
//			return Error.Type.NETWORK_ISSUE;
//		}
//		catch (UnknownHostException e) {
//			this.log.error("Server::getConfiguration: exception UnknownHostException " + e);
//			return Error.Type.NETWORK_ISSUE;
//		}
//		catch (UnsupportedEncodingException e) {
//			this.log.error("Server::getConfiguration: exception UnsupportedEncodingException " + e);
//			return Error.Type.UNKNOWN;
//		}
		catch (IOException e) {
			this.log.error("Server::getConfiguration: exception IOException " + e);
			return Error.Type.UNKNOWN;
		}
//		finally {
//			if (connection != null) {
//				connection.disconnect();
//			}
//		}
		return Error.Type.OK;
	}
	
	public Job requestJob() throws FermeException {
		this.log.debug("Server::requestJob");
		String url_contents = "";


//		HttpURLConnection connection = null;
		try {
			OS os = OS.getOS();
			long maxMemory = this.user_config.getMaxMemory();
			long freeMemory = os.getFreeMemory();
			if (maxMemory < 0) {
				maxMemory = freeMemory;
			}
			else if (freeMemory > 0 && maxMemory > 0) {
				maxMemory = Math.min(maxMemory, freeMemory);
			}
			//String url = String.format("%s?computemethod=%s&cpu_cores=%s&ram_max=%s&rendertime_max=%s",
			// this.getPage("request-job"),
			// ,
			// maxMemory,
			//
			// );
			String gpuModel = "";
			String gpuType = "";
			long gpuVram = 0;
			if (this.user_config.getComputeMethod() != ComputeType.CPU && this.user_config.getGPUDevice() != null) {
				gpuModel = this.user_config.getGPUDevice().getModel();
				gpuType = this.user_config.getGPUDevice().getType();
				gpuVram = this.user_config.getGPUDevice().getMemory();
			}


			Response<JobInfos> response = service.requestJob(
					serverConfig.getRequestEndPoint("request-job").getPath(),
					this.user_config.computeMethodToInt(),
					(this.user_config.getNbCores() == -1) ? os.getCPU().cores() : this.user_config.getNbCores(),
					maxMemory,
					this.user_config.getMaxRenderTime(),
					gpuModel,
					gpuVram,
					gpuType
			).execute();



			if (response.code() != HttpURLConnection.HTTP_OK  /* || newConfig.headers().get("contentType) .startsWith("text/html") */ ) {
				throw new FermeExceptionBadResponseFromServer();
			}

			//				if (r == HttpURLConnection.HTTP_UNAVAILABLE || r == HttpURLConnection. HTTP_CLIENT_TIMEOUT) {
			//					// most likely varnish is up but apache down
			//					throw new FermeServerDown();
			//				}

			System.out.println("response " + response);
			JobInfos jobData = response.body();
			System.out.println("jobData " + jobData);

			this.lastRequestTime = new Date().getTime();
			ServerCode serverCode = ServerCode.fromInt(jobData.getStatus());
//			ServerCode ret = Utils.statusIsOK(document, "jobrequest");
			if (serverCode != ServerCode.OK) {
				switch (serverCode) {
					case JOB_REQUEST_NOJOB:
						//handleFileMD5DeleteDocument(document, "jobrequest");
						return null;
					case JOB_REQUEST_ERROR_NO_RENDERING_RIGHT:
						throw new FermeExceptionNoRightToRender();
					case JOB_REQUEST_ERROR_DEAD_SESSION:
						throw new FermeExceptionNoSession();
					case JOB_REQUEST_ERROR_SESSION_DISABLED:
						throw new FermeExceptionSessionDisabled();
					case JOB_REQUEST_ERROR_INTERNAL_ERROR:
						throw new FermeExceptionBadResponseFromServer();
					case JOB_REQUEST_ERROR_RENDERER_NOT_AVAILABLE:
						throw new FermeExceptionNoRendererAvailable();
					case JOB_REQUEST_SERVER_IN_MAINTENANCE:
						throw new FermeExceptionServerInMaintenance();
					case JOB_REQUEST_SERVER_OVERLOADED:
						throw new FermeExceptionServerOverloaded();
					default:
						throw new FermeException("error requestJob: status is not ok (it's " + serverCode + ")");
				}
			}

//			if (r == HttpURLConnection.HTTP_OK && contentType.startsWith("text/xml")) {
//				DataInputStream in = new DataInputStream(connection.getInputStream());
//				Document document = null;
//				try {
//					document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
//				}
//				catch (SAXException e) {
//					throw new FermeException("error requestJob: parseXML failed, SAXException " + e);
//				}
//				catch (IOException e) {
//					throw new FermeException("error requestJob: parseXML failed IOException " + e);
//				}
//				catch (ParserConfigurationException e) {
//					throw new FermeException("error requestJob: parseXML failed ParserConfigurationException " + e);
//				}
//
//
//
//				handleFileMD5DeleteDocument(document, "jobrequest");
//
//				Element a_node = null;
//				NodeList ns = null;
//
//				ns = document.getElementsByTagName("stats");
//				if (ns.getLength() == 0) {
//					throw new FermeException("error requestJob: parseXML failed, no 'frame' node");
//				}
//				a_node = (Element) ns.item(0);
//
//				ns = document.getElementsByTagName("job");
//				if (ns.getLength() == 0) {
//					throw new FermeException("error requestJob: parseXML failed, no 'job' node");
//				}
//				Element job_node = (Element) ns.item(0);
//
//				ns = job_node.getElementsByTagName("renderer");
//				if (ns.getLength() == 0) {
//					throw new FermeException("error requestJob: parseXML failed, node 'job' have no sub-node 'renderer'");
//				}
//				Element renderer_node = (Element) ns.item(0);
//
				String script = "import bpy\n";

				// blender 2.7x
				script += "try:\n";
				script += "\tbpy.context.user_preferences.filepaths.temporary_directory = \"" + this.user_config.getWorkingDirectory().getAbsolutePath().replace("\\", "\\\\") + "\"\n";
				script += "except AttributeError:\n";
				script += "\tpass\n";

				// blender 2.80
				script += "try:\n";
				script += "\tbpy.context.preferences.filepaths.temporary_directory = \"" + this.user_config.getWorkingDirectory().getAbsolutePath().replace("\\", "\\\\") + "\"\n";
				script += "except AttributeError:\n";
				script += "\tpass\n";


				script += jobData.getRenderTask().getScript();
/*

<?xml version="1.0" encoding="UTF-8"?>
<jobrequest status="0">
    <stats credits_session="0" credits_total="48186" frame_remaining="0" waiting_project="0" connected_machine="25" />
    <job id="1" use_gpu="1" archive_md5="d8b01c8656cca48b8a22bc7048783fe9" path="compute-method.blend" frame="0340" synchronous_upload="1" extras="" name="Can Blender be launched?" password="">
        <renderer md5="3d0e05e7a43ae213eccf33c47b5900c5" commandline=".e --factory-startup --disable-autoexec -b .c -o .o -f .f -x 1" update_method="remainingtime" />
        <script>....</script>
    </job>
</jobrequest>


 */

				Job a_job = new Job(
						this.user_config,
						this.client.getGui(),
						this.client.getLog(),
						jobData.getRenderTask().getId(),
						jobData.getRenderTask().getFrame(),
						jobData.getRenderTask().getPath().replace("/", File.separator),
						jobData.getRenderTask().getUseGpu() == 1,
						jobData.getRenderTask().getRendererInfos().getCommandline(),
						script,
						jobData.getRenderTask().getArchive_md5(),
						jobData.getRenderTask().getRendererInfos().getMd5(),
						jobData.getRenderTask().getName(),
						jobData.getRenderTask().getPassword(),
						jobData.getRenderTask().getExtras(),
						jobData.getRenderTask().getSynchronous_upload().equals("1"),
						jobData.getRenderTask().getRendererInfos().getUpdate_method()
						);

				this.client.getGui().displayStats(
						new Stats(
								jobData.getSessionStats().getRemainingFrames(),
								jobData.getSessionStats().getPointsEarnedByUser(),
								jobData.getSessionStats().getPointsEarnedOnSession(),
								jobData.getSessionStats().getRenderableProjects(),
								jobData.getSessionStats().getWaitingProjects(),
								jobData.getSessionStats().getConnectedMachines()));

				return a_job;
//			}
//			else {
//				System.out.println("Server::requestJob url " + url_contents + " r " + r + " contentType " + contentType);
//				if (r == HttpURLConnection.HTTP_UNAVAILABLE || r == HttpURLConnection. HTTP_CLIENT_TIMEOUT) {
//					// most likely varnish is up but apache down
//					throw new FermeServerDown();
//				}
//				else if (r == HttpURLConnection.HTTP_OK && contentType.startsWith("text/html")) {
//					throw new FermeExceptionBadResponseFromServer();
//				}
//				InputStream in = connection.getInputStream();
//				String line;
//				BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
//				while ((line = reader.readLine()) != null) {
//					System.out.print(line);
//				}
//				System.out.println("");
//			}
		}
		catch (FermeException e) {
			throw e;
		}
		catch (NoRouteToHostException e) {
			throw new FermeServerDown();
		}
		catch (UnknownHostException e) {
			throw new FermeServerDown();
		}
		catch (Exception e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			throw new FermeException("error requestJob: unknown exception " + e + " stacktrace: " + sw.toString());
		}
		//throw new FermeException("error requestJob, end of function");
	}
	
	public HttpURLConnection HTTPRequest(String url_) throws IOException {
		return this.HTTPRequest(url_, null);
	}
	
	public HttpURLConnection HTTPRequest(String url_, String data_) throws IOException {
		this.log.debug("Server::HTTPRequest url(" + url_ + ")");
		HttpURLConnection connection = null;
		URL url = new URL(url_);
		
		connection = (HttpURLConnection) url.openConnection();
		connection.setDoInput(true);
		connection.setDoOutput(true);
		connection.setInstanceFollowRedirects(true);
		connection.setRequestMethod("GET");
		
		if (url_.startsWith("https://")) {
			try {
				SSLContext sc;
				sc = SSLContext.getInstance("SSL");
				sc.init(null, new TrustManager[] { this }, null);
				SSLSocketFactory factory = sc.getSocketFactory();
				((HttpsURLConnection) connection).setSSLSocketFactory(factory);
				((HttpsURLConnection) connection).setHostnameVerifier(this);
			}
			catch (NoSuchAlgorithmException e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				this.log.debug("Server::HTTPRequest NoSuchAlgorithmException " + e + " stacktrace: " + sw.toString());
				return null;
			}
			catch (KeyManagementException e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				this.log.debug("Server::HTTPRequest KeyManagementException " + e + " stacktrace: " + sw.toString());
				return null;
			}
		}
		
		if (data_ != null) {
			connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
			connection.setRequestMethod("POST");
			OutputStreamWriter out = new OutputStreamWriter(connection.getOutputStream());
			out.write(data_);
			out.flush();
			out.close();
		}
		
		// actually use the connection to, in case of timeout, generate an exception
		connection.getResponseCode();
		
		this.lastRequestTime = new Date().getTime();
		
		return connection;
	}
	
	public int HTTPGetFile(String url_, String destination_, Gui gui_, String status_) throws FermeExceptionNoSpaceLeftOnDevice {
		// the destination_ parent directory must exist
		try {
			HttpURLConnection httpCon = this.HTTPRequest(url_);
			
			InputStream inStrm = httpCon.getInputStream();
			if (httpCon.getResponseCode() != HttpURLConnection.HTTP_OK) {
				this.log.error("Server::HTTPGetFile(" + url_ + ", ...) HTTP code is not " + HttpURLConnection.HTTP_OK + " it's " + httpCon.getResponseCode());
				return -1;
			}
			int size = httpCon.getContentLength();
			long start = new Date().getTime();
			
			FileOutputStream fos = new FileOutputStream(destination_);
			byte[] ch = new byte[512 * 1024];
			int nb;
			long written = 0;
			long last_gui_update = 0; // size in byte
			while ((nb = inStrm.read(ch)) != -1) {
				fos.write(ch, 0, nb);
				written += nb;
				if ((written - last_gui_update) > 1000000) { // only update the gui every 1MB
					gui_.status(String.format(status_, (int) (100.0 * written / size)));
					last_gui_update = written;
				}
			}
			fos.close();
			inStrm.close();
			long end = new Date().getTime();
			this.log.debug(String.format("File downloaded at %.1f kB/s, written %d B", ((float) (size / 1000)) / ((float) (end - start) / 1000), written));
			this.lastRequestTime = new Date().getTime();
			return 0;
		}
		catch (Exception e) {
			if (Utils.noFreeSpaceOnDisk(new File(destination_).getParent())) {
				throw new FermeExceptionNoSpaceLeftOnDevice();
			}
			
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			this.log.error("Server::HTTPGetFile Exception " + e + " stacktrace " + sw.toString());
		}
		this.log.debug("Server::HTTPGetFile(" + url_ + ", ...) will failed (end of function)");
		return -2;
	}
	
	public ServerCode HTTPSendFile(String surl, String file1) {
		this.log.debug("Server::HTTPSendFile(" + surl + "," + file1 + ")");
		
		HttpURLConnection conn = null;
		DataOutputStream dos = null;
		BufferedReader inStream = null;
		
		String exsistingFileName = file1;
		File fFile2Snd = new File(exsistingFileName);
		
		String lineEnd = "\r\n";
		String twoHyphens = "--";
		String boundary = "***232404jkg4220957934FW**";
		
		int bytesRead, bytesAvailable, bufferSize;
		byte[] buffer;
		int maxBufferSize = 1 * 1024 * 1024;
		
		String urlString = surl;
		
		try {
			FileInputStream fileInputStream = new FileInputStream(new File(exsistingFileName));
			URL url = new URL(urlString);
			
			conn = (HttpURLConnection) url.openConnection();
			conn.setDoInput(true);
			conn.setDoOutput(true);
			conn.setInstanceFollowRedirects(true);
			conn.setUseCaches(false);
			
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Connection", "Keep-Alive");
			conn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
			
			if (urlString.startsWith("https://")) {
				try {
					SSLContext sc;
					sc = SSLContext.getInstance("SSL");
					sc.init(null, new TrustManager[] { this }, null);
					SSLSocketFactory factory = sc.getSocketFactory();
					((HttpsURLConnection) conn).setSSLSocketFactory(factory);
					((HttpsURLConnection) conn).setHostnameVerifier(this);
				}
				catch (NoSuchAlgorithmException e) {
					this.log.error("Server::HTTPSendFile, exception NoSuchAlgorithmException " + e);
					try {
						fileInputStream.close();
					}
					catch (Exception e1) {
						
					}
					return ServerCode.UNKNOWN;
				}
				catch (KeyManagementException e) {
					this.log.error("Server::HTTPSendFile, exception KeyManagementException " + e);
					try {
						fileInputStream.close();
					}
					catch (Exception e1) {
						
					}
					return ServerCode.UNKNOWN;
				}
			}
			
			dos = new DataOutputStream(conn.getOutputStream());
			dos.writeBytes(twoHyphens + boundary + lineEnd);
			dos.writeBytes("Content-Disposition: form-data; name=\"file\";" + " filename=\"" + fFile2Snd.getName() + "\"" + lineEnd);
			dos.writeBytes(lineEnd);
			
			bytesAvailable = fileInputStream.available();
			bufferSize = Math.min(bytesAvailable, maxBufferSize);
			buffer = new byte[bufferSize];
			
			bytesRead = fileInputStream.read(buffer, 0, bufferSize);
			
			while (bytesRead > 0) {
				dos.write(buffer, 0, bufferSize);
				bytesAvailable = fileInputStream.available();
				bufferSize = Math.min(bytesAvailable, maxBufferSize);
				bytesRead = fileInputStream.read(buffer, 0, bufferSize);
			}
			
			dos.writeBytes(lineEnd);
			dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
			fileInputStream.close();
			dos.flush();
			dos.close();
		}
		catch (MalformedURLException e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.error("Server::HTTPSendFile, MalformedURLException " + e + " stacktrace " + sw.toString());
			return ServerCode.UNKNOWN;
		}
		catch (IOException e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.error("Server::HTTPSendFile, IOException " + e + " stacktrace " + sw.toString());
			return ServerCode.UNKNOWN;
		}
		catch (OutOfMemoryError e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.error("Server::HTTPSendFile, OutOfMemoryError " + e + " stacktrace " + sw.toString());
			return ServerCode.JOB_VALIDATION_ERROR_UPLOAD_FAILED;
		}
		catch (Exception e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.error("Server::HTTPSendFile, Exception " + e + " stacktrace " + sw.toString());
			return ServerCode.UNKNOWN;
		}
		
		int r;
		try {
			r = conn.getResponseCode();
		}
		catch (IOException e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.debug("Server::HTTPSendFile IOException " + e + " stacktrace: " + sw.toString());
			return ServerCode.UNKNOWN;
		}
		String contentType = conn.getContentType();
		
		if (r == HttpURLConnection.HTTP_OK && contentType.startsWith("text/xml")) {
			DataInputStream in;
			try {
				in = new DataInputStream(conn.getInputStream());
			}
			catch (IOException e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				this.log.debug("Server::HTTPSendFile IOException " + e + " stacktrace: " + sw.toString());
				return ServerCode.UNKNOWN;
			}
			Document document = null;
			try {
				document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
			}
			catch (SAXException e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				this.log.debug("Server::HTTPSendFile SAXException " + e + " stacktrace: " + sw.toString());
				return ServerCode.UNKNOWN;
			}
			catch (IOException e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				this.log.debug("Server::HTTPSendFile IOException " + e + " stacktrace: " + sw.toString());
				return ServerCode.UNKNOWN;
			}
			catch (ParserConfigurationException e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				this.log.debug("Server::HTTPSendFile ParserConfigurationException " + e + " stacktrace: " + sw.toString());
				return ServerCode.UNKNOWN;
			}
			
			this.lastRequestTime = new Date().getTime();
			
			ServerCode ret1 = Utils.statusIsOK(document, "jobvalidate");
			if (ret1 != ServerCode.OK) {
				this.log.error("Server::HTTPSendFile wrong status (is " + ret1 + ")");
				return ret1;
			}
			return ServerCode.OK;
		}
		else if (r == HttpURLConnection.HTTP_OK && contentType.startsWith("text/html")) {
			return ServerCode.ERROR_BAD_RESPONSE;
		}
		else {
			try {
				inStream = new BufferedReader(new InputStreamReader(conn.getInputStream()));
				
				String str;
				while ((str = inStream.readLine()) != null) {
					System.out.println(str);
					System.out.println("");
				}
				inStream.close();
			}
			catch (IOException ioex) {
			}
		}
		return ServerCode.UNKNOWN;
	}
	
	public byte[] getLastRender() {
		try {
			Response<ResponseBody> response = service.getLastRenderThumbnail(serverConfig.getRequestEndPoint("last-render-frame").getPath()).execute();
			if (response.isSuccessful()) {
				InputStream inStrm = response.body().byteStream();
				int size = (int)response.body().contentLength();
				if (size <= 0) {
					this.log.debug("Server::getLastRender size is negative (size: " + size + ")");
					return null;
				}

				byte[] ret = new byte[size];
				byte[] ch = new byte[512 * 1024];
				int n = 0;
				int i = 0;
				while ((n = inStrm.read(ch)) != -1) {
					System.arraycopy(ch, 0, ret, i, n);
					i += n;
				}
				inStrm.close();
				return ret;
			}
		}
		catch (Exception e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			this.log.debug("Server::getLastRender Exception " + e + " stacktrace: " + sw.toString());
		}
		return null;
	}
	
	private String generateXMLForMD5cache() {
		String xml_str = null;
		try {
			DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
			Document document_cache = docBuilder.newDocument();
			
			Element rootElement = document_cache.createElement("cache");
			document_cache.appendChild(rootElement);
			
			List<File> local_files = this.user_config.getLocalCacheFiles();
			for (File local_file : local_files) {
				Element node_file = document_cache.createElement("file");
				rootElement.appendChild(node_file);
				try {
					String extension = local_file.getName().substring(local_file.getName().lastIndexOf('.')).toLowerCase();
					String name = local_file.getName().substring(0, local_file.getName().length() - 1 * extension.length());
					if (extension.equals(".zip")) {
						node_file.setAttribute("md5", name);
					}
				}
				catch (StringIndexOutOfBoundsException e) { // because the file does not have an . his path
				}
			}
			
			TransformerFactory tf = TransformerFactory.newInstance();
			Transformer transformer = tf.newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
			StringWriter writer = new StringWriter();
			transformer.transform(new DOMSource(document_cache), new StreamResult(writer));
			xml_str = writer.getBuffer().toString();
		}
		catch (TransformerConfigurationException e) {
			this.log.debug("Server::generateXMLForMD5cache " + e);
		}
		catch (TransformerException e) {
			this.log.debug("Server::generateXMLForMD5cache " + e);
		}
		catch (ParserConfigurationException e) {
			this.log.debug("Server::generateXMLForMD5cache " + e);
		}
		
		return xml_str;
	}
	
	private void handleFileMD5DeleteDocument(Document document, String root_nodename) {
		NodeList ns = document.getElementsByTagName(root_nodename);
		if (ns.getLength() > 0) {
			Element root_node = (Element) ns.item(0);
			ns = root_node.getElementsByTagName("file");
			if (ns.getLength() > 0) {
				for (int i = 0; i < ns.getLength(); ++i) {
					Element file_node = (Element) ns.item(i);
					if (file_node.hasAttribute("md5") && file_node.hasAttribute("action") && file_node.getAttribute("action").equals("delete")) {
						String path = this.user_config.getWorkingDirectory().getAbsolutePath() + File.separatorChar + file_node.getAttribute("md5");
						this.log.debug("Server::handleFileMD5DeleteDocument delete old file " + path);
						File file_to_delete = new File(path + ".zip");
						file_to_delete.delete();
						Utils.delete(new File(path));
					}
				}
			}
		}
	}
	
	@Override
	public void checkClientTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
	}
	
	@Override
	public void checkServerTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
	}
	
	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return null;
	}
	
	@Override
	public boolean verify(String arg0, SSLSession arg1) {
		return true; // trust every ssl certificate
	}
}
