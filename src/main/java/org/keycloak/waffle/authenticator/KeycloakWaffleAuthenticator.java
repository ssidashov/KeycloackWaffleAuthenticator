/**
 * @author bogdan
 */

package org.keycloak.waffle.authenticator;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.events.Errors;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.services.ServicesLogger;
import org.keycloak.waffle.NTLMCredentialInput;
import waffle.util.AuthorizationHeader;
import waffle.windows.auth.IWindowsAuthProvider;
import waffle.windows.auth.IWindowsIdentity;
import waffle.windows.auth.IWindowsSecurityContext;
import waffle.windows.auth.impl.WindowsAuthProviderImpl;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * main implementation class, reworked for nginx compatibility
 * @author bogdan
 *
 */
public class KeycloakWaffleAuthenticator implements Authenticator {

    private static final Logger logger = Logger.getLogger(KeycloakWaffleAuthenticator.class);

    private static final String CONNECTION_SEPARATOR = ":";
	private static final String KEEP_ALIVE = "keep-alive";
	private static final String CONNECTION = "Connection";
	private static final String WWW_AUTHENTICATE2 = "WWW-Authenticate";
	private static final String NTLM2 = "NTLM ";
	private static final String NTLM = "NTLM";
	private static final String WWW_AUTHENTICATE = "WWW-AUTHENTICATE";
	private static final String AUTHORIZATION = "Authorization";
	private static final String X_FORWARDED_PORT = "X-Forwarded-Port";
	private static final String X_FORWARDED_FOR = "X-Forwarded-For";
	private final IWindowsAuthProvider auth = new WindowsAuthProviderImpl();	
       
    private String getRequestHeader(AuthenticationFlowContext context, String header) {
    	return context.getHttpRequest().getHttpHeaders().getRequestHeaders().getFirst(header);
    }
    
	/**
	 * modification due to proxy handling
	 * the X-Forwarded-Port header is set up to remote port, for nginx use:
	 * proxy_set_header X-Forwarded-Port     $remote_port;
	 */
	private IWindowsIdentity getNTLMIdentity(AuthenticationFlowContext context) throws IOException {
		//added by proxies
		String xForwarded = getRequestHeader(context, X_FORWARDED_FOR); //comma separated
		String xPort = getRequestHeader(context, X_FORWARDED_PORT); 	//if it contains : must split	
		String auth = getRequestHeader(context, AUTHORIZATION); 			
		if (auth == null) {
			Response response = Response.noContent().status(Response.Status.UNAUTHORIZED).header(WWW_AUTHENTICATE, NTLM).build();
        	context.forceChallenge(response);
	        return null;
		}
        logger.info("auth is " + auth);
		if (auth.startsWith(NTLM2)) {
			//waffle part
			final AuthorizationHeader authorizationHeader = new CustomAuthorizationHeader(context);
			final boolean ntlmPost = authorizationHeader.isNtlmType1PostAuthorizationHeader();
	        // maintain a connection-based session for NTLM tokens
			String connectionId = null;
			if(xForwarded != null && xPort != null) {
				connectionId = getConnectionId(xForwarded, xPort);
			} else {
                logger.info("headers...");
                logger.info(context.getConnection().getRemoteAddr());
                logger.info(context.getConnection().getRemotePort());
				context.getHttpRequest().getHttpHeaders().getRequestHeaders().forEach((a, b) -> {
                    logger.info(a);
                    logger.info(b);
				});
				connectionId = String.join(CONNECTION_SEPARATOR, context.getConnection().getRemoteAddr(), String.valueOf(context.getConnection().getRemotePort()));
			}
	        
	        final String securityPackage = authorizationHeader.getSecurityPackage();			
			System.out.printf("security package: %s, connection id: %s\n", securityPackage, connectionId);
	        if (ntlmPost) {
                logger.info("was ntlmPost");
	            // type 2 NTLM authentication message received
	            this.auth.resetSecurityToken(connectionId);
	        }

	        final byte[] tokenBuffer = authorizationHeader.getTokenBytes();
	        IWindowsSecurityContext securityContext = null;
	        try {
	        	 securityContext = this.auth.acceptSecurityToken(connectionId, tokenBuffer, securityPackage);
	        } catch (Exception e) {
				Response response = Response.noContent().status(Response.Status.UNAUTHORIZED).build();
	        	context.forceChallenge(response);
	        	//this can be context.cancelLogin();
	        	context.attempted();
		        return null;
	        }
	        final byte[] continueTokenBytes = securityContext.getToken();
	        ResponseBuilder responseBuilder = Response.noContent();
	        if (continueTokenBytes != null && continueTokenBytes.length > 0) {
	        	//type 2 message, the challenge
	            final String continueToken = Base64.getEncoder().encodeToString(continueTokenBytes);
	            responseBuilder.header(WWW_AUTHENTICATE2, securityPackage + " " + continueToken);
	        }
            logger.info("continue required: " + Boolean.valueOf(securityContext.isContinue()));
	        
	        if (securityContext.isContinue() || ntlmPost) {
	        	responseBuilder.header(CONNECTION, KEEP_ALIVE);
	        	responseBuilder.status(Response.Status.UNAUTHORIZED).build();
	        	context.forceChallenge(responseBuilder.build());
	            return null;
	        }
	        final IWindowsIdentity identity = securityContext.getIdentity();
	        securityContext.dispose();
	        return identity;	
		}
		return null;
	}

	/**
	 * get connection id for proxy headers
	 * @param xForwarded
	 * @param xPort
	 * @return
	 */
	private String getConnectionId(String xForwarded, String xPort) {
		String host = xForwarded;
		String port = xPort;
		if(xForwarded.contains(",")) {
			host = xForwarded.split(",")[0];
		}
		if(port.contains(CONNECTION_SEPARATOR)) {
			String[] ports = xPort.split(CONNECTION_SEPARATOR);
			xPort = ports[ports.length -1];
		}
		return String.join(CONNECTION_SEPARATOR, host, port);
	}

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        
        IWindowsIdentity identity = null;
		try {
			identity = getNTLMIdentity(context);
		} catch (IOException e) {
            logger.warn("Cannot authenticate ntlm identity", e);
			return;
		}
		if(identity == null) 
			return;
        logger.info("identity is " + identity.getFqn());

        if (tryToLoginByUsername(context, identity)) return;

        NTLMCredentialInput ntlmCredentialInput = new NTLMCredentialInput(identity);
        CredentialValidationOutput output =
                context.getSession().userCredentialManager().authenticate(context.getSession(), context.getRealm(), ntlmCredentialInput);

        if (output == null) {
            logger.warn("Received ntlm token, but there is no user storage provider that handles ntlm credentials.");
            context.attempted();
            return;
        }

        if (output.getAuthStatus() == CredentialValidationOutput.Status.AUTHENTICATED) {
            context.setUser(output.getAuthenticatedUser());
            if (output.getState() != null && !output.getState().isEmpty()) {
                for (Map.Entry<String, String> entry : output.getState().entrySet()) {
                    context.getAuthenticationSession().setUserSessionNote(entry.getKey(), entry.getValue());
                }
            }
            context.success();
            logger.info("KeycloakWaffleAuthenticator :: authenticate :: success");
        } else {
            context.getEvent().error(Errors.INVALID_USER_CREDENTIALS);
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
        }
    }

    private boolean tryToLoginByUsername(AuthenticationFlowContext context, IWindowsIdentity identity) {
        //the fqn has the form domain\\user
        String username = extractUserWithoutDomain(identity.getFqn());
        UserModel user = null;
        try {
            user = KeycloakModelUtils.findUserByNameOrEmail(context.getSession(), context.getRealm(), username);
        } catch (ModelDuplicateException mde) {
            ServicesLogger.LOGGER.modelDuplicateException(mde);
            // Could happen during federation import
            return true;
        }
        if(user != null){
            context.setUser(user);
            context.success();
            return true;
        }
        return false;
    }

    private String extractUserWithoutDomain(String username) {
        if (username.contains("\\")) {
            username = username.substring(username.indexOf("\\") + 1, username.length());
        }
        return username;
    }

    @Override
    public boolean requiresUser() {
    	logger.info("KeycloakWaffleAuthenticator :: requiresUser");
        //return true;
    	return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        logger.info("KeycloakWaffleAuthenticator :: configuredFor");
        return false;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        logger.info("KeycloakWaffleAuthenticator :: setRequiredActions");
    }

    @Override
    public void close() {
        logger.info("KeycloakWaffleAuthenticator :: close");

    }

	@Override
	public void action(AuthenticationFlowContext context) {
		// TODO Auto-generated method stub
		
	}
}
