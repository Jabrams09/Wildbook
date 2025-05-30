/**********************************************************************
   Copyright (c) 2009 Erik Bengtson and others. All rights reserved. Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
      WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations
      under the License.


   Contributors:
    ...
 **********************************************************************/
package org.ecocean.servlet;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collection;
import java.util.List;
import java.util.StringTokenizer;

import java.lang.reflect.Method;
import org.ecocean.CommonConfiguration;
import org.ecocean.Encounter;
import org.ecocean.security.Collaboration;
import org.ecocean.shepherd.core.Shepherd;
import org.ecocean.shepherd.utils.ShepherdState;
import org.ecocean.Util;

import javax.jdo.Query;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import org.datanucleus.api.jdo.JDOPersistenceManager;
import org.datanucleus.api.rest.orgjson.JSONArray;
import org.datanucleus.api.rest.orgjson.JSONException;
import org.datanucleus.api.rest.orgjson.JSONObject;
import org.datanucleus.api.rest.RESTUtils;
import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.ClassNotResolvedException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusObjectNotFoundException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.ExecutionContext;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.IdentityType;
import org.datanucleus.PersistenceNucleusContext;
import org.datanucleus.util.NucleusLogger;

import java.io.OutputStream;
import java.util.zip.*;

/**
 * This servlet replaces the REST servlet in a non-recursive way, useful for large queries such as on Encounter Search
 * <ul>
 * <li>GET (retrieve/query)</li>
 * <li>POST (update/insert)</li>
 * <li>PUT (update/insert)</li>
 * <li>DELETE (delete)</li>
 * <li>HEAD (validate)</li>
 * </ul>
 */
public class LightRestServlet extends HttpServlet {
    private static final long serialVersionUID = -4445182084242929362L;

    private static final String[] Encounter_Light_Str_Fields = {
        "catalogNumber", "individualID", "occurrenceID", "sex", "otherCatalogNumbers",
            "verbatimLocality", "locationID", "submitterName", "submitterProject", "submitterID",
            "submitterOrganization", "genus", "specificEpithet", "dwcDateAdded", "modified"
    };
    private static final String[] Encounter_Light_Int_Fields = { "year", "month", "day" };
    private static final String[] Encounter_Light_Long_Fields = { "dwcDateAddedLong" };

    public static final NucleusLogger LOGGER_REST = NucleusLogger.getLoggerInstance(
        "DataNucleus.REST");

    PersistenceNucleusContext nucCtx;
    HttpServletRequest thisRequest;

    public void init(ServletConfig config)
    throws ServletException {
        super.init(config);
    }

    /**
     * Convenience method to get the next token after a "/".
     * @param req The request
     * @return The next token
     */
    private String getNextTokenAfterSlash(HttpServletRequest req) {
        String path = req.getRequestURI().substring(req.getContextPath().length() +
            req.getServletPath().length());
        StringTokenizer tokenizer = new StringTokenizer(path, "/");

        return tokenizer.nextToken();
    }

    /**
     * Convenience accessor to get the id, following a "/".
     * @param req The request
     * @return The id (or null if no slash)
     */
    private Object getId(HttpServletRequest req) {
        ClassLoaderResolver clr = nucCtx.getClassLoaderResolver(RestServlet.class.getClassLoader());
        String path = req.getRequestURI().substring(req.getContextPath().length() +
            req.getServletPath().length());
        StringTokenizer tokenizer = new StringTokenizer(path, "/");
        String className = tokenizer.nextToken();
        AbstractClassMetaData cmd = nucCtx.getMetaDataManager().getMetaDataForClass(className, clr);
        String id = null;

        if (tokenizer.hasMoreTokens()) {
            // "id" single-field specified in URL
            id = tokenizer.nextToken();
            if (id == null || cmd == null) {
                return null;
            }
            Object identity = RESTUtils.getIdentityForURLToken(cmd, id, nucCtx);
            if (identity != null) {
                return identity;
            }
        }
        // "id" must have been specified in the content of the request
        try {
            if (id == null && req.getContentLength() > 0) {
                char[] buffer = new char[req.getContentLength()];
                req.getReader().read(buffer);
                id = new String(buffer);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (id == null || cmd == null) {
            return null;
        }
        try {
            // assume it's a JSONObject
            id = URLDecoder.decode(id, "UTF-8");
            JSONObject jsonobj = new JSONObject(id);
            return RESTUtils.getNonPersistableObjectFromJSONObject(jsonobj,
                    clr.classForName(cmd.getObjectidClass()), nucCtx);
        } catch (JSONException ex) {
            // not JSON syntax
        } catch (UnsupportedEncodingException e) {
            LOGGER_REST.error("Exception caught when trying to determine id", e);
        }
        return id;
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
    throws ServletException, IOException {
        System.out.println("        LIGHTREST: doGet called");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        // getPMF(req);
        // Retrieve any fetch group that needs applying to the fetch
        String fetchParam = req.getParameter("fetch");
        String encodings = req.getHeader("Accept-Encoding");
        boolean useCompression = ((encodings != null) && (encodings.indexOf("gzip") > -1));
        Shepherd myShepherd = new Shepherd(req);
        myShepherd.setAction("LightRestServlet.class.GET");

        try {
            String token = getNextTokenAfterSlash(req);
            if ((token.equalsIgnoreCase("query") || token.equalsIgnoreCase("jdoql")) &&
                (req.getRemoteUser() != null)) {
                // GET "/query?the_query_details" or GET "/jdoql?the_query_details" where "the_query_details" is "SELECT FROM ... WHERE ... ORDER BY
                // ..."
                String queryString = URLDecoder.decode(req.getQueryString(), "UTF-8");
                // PersistenceManager pm = pmf.getPersistenceManager();
                String servletID = Util.generateUUID();
                // ShepherdState.setShepherdState("LightRestServlet.class"+"_"+servletID, "new");

                System.out.println("        LIGHTREST: has queryString " + queryString);

                try {
                    myShepherd.beginDBTransaction();
                    // ShepherdState.setShepherdState("LightRestServlet.class"+"_"+servletID, "begin");

                    Query query = myShepherd.getPM().newQuery("JDOQL", queryString);
                    if (fetchParam != null) {
                        query.getFetchPlan().setGroup(fetchParam);
                    }
                    Object result = filterResult(query.execute());
                    System.out.println("        LIGHTREST: executed query " + query);
                    if (result instanceof Collection) {
                        JSONArray jsonobj = convertToJson(req, (Collection)result,
                            ((JDOPersistenceManager)myShepherd.getPM()).getExecutionContext(),
                            myShepherd);
                        tryCompress(req, resp, jsonobj, useCompression);
                    } else {
                        JSONObject jsonobj = convertToJson(req, result,
                            ((JDOPersistenceManager)myShepherd.getPM()).getExecutionContext(),
                            myShepherd);
                        System.out.println("        LIGHTREST: has jsonobj, about to tryCompress ");
                        tryCompress(req, resp, jsonobj, useCompression);
                    }
                    query.closeAll();
                    resp.setHeader("Content-Type", "application/json");
                    resp.setStatus(200);
                    myShepherd.commitDBTransaction();
                } catch (Exception e) {
                    System.out.println("Exception on lightRestServlet!");
                    e.printStackTrace();
                } finally {
                    if (myShepherd.getPM().currentTransaction().isActive()) {
                        myShepherd.rollbackDBTransaction();
                    }
                    myShepherd.closeDBTransaction();
                }
                return;
            }
            
            else {
                String className = token;
                ClassLoaderResolver clr = nucCtx.getClassLoaderResolver(
                    LightRestServlet.class.getClassLoader());
                AbstractClassMetaData cmd = nucCtx.getMetaDataManager().getMetaDataForEntityName(
                    className);
                try {
                    if (cmd == null) {
                        cmd = nucCtx.getMetaDataManager().getMetaDataForClass(className, clr);
                    }
                } catch (ClassNotResolvedException ex) {
                    JSONObject error = new JSONObject();
                    error.put("exception", ex.getMessage());
                    resp.getWriter().write(error.toString());
                    resp.setStatus(404);
                    resp.setHeader("Content-Type", "application/json");
                    return;
                }
                Object id = getId(req);
                if (id == null) {
                    // Find objects by type or by query
                    if (req.getRemoteUser() != null) {
                        try {
                            // get the whole extent for this candidate
                            String queryString = "SELECT FROM " + cmd.getFullClassName();
                            if (req.getQueryString() != null) {
                                // query by filter for this candidate
                                queryString += " WHERE " + URLDecoder.decode(req.getQueryString(),
                                    "UTF-8");
                            }
                            if (fetchParam != null) {
                                myShepherd.getPM().getFetchPlan().setGroup(fetchParam);
                            }
                            try {
                                myShepherd.getPM().currentTransaction().begin();
                                Query query = myShepherd.getPM().newQuery("JDOQL", queryString);
                                List result = (List)filterResult(query.execute());
                                JSONArray jsonobj = convertToJson(req, result,
                                    ((JDOPersistenceManager)myShepherd.getPM()).getExecutionContext(),
                                    myShepherd);
                                tryCompress(req, resp, jsonobj, useCompression);
                                query.closeAll();
                                resp.setHeader("Content-Type", "application/json");
                                resp.setStatus(200);
                            } finally {
                                if (myShepherd.getPM().currentTransaction().isActive()) {
                                    myShepherd.rollbackDBTransaction();
                                }
                                myShepherd.closeDBTransaction();
                            }
                            return;
                        } catch (NucleusUserException e) {
                            JSONObject error = new JSONObject();
                            error.put("exception", e.getMessage());
                            resp.getWriter().write(error.toString());
                            resp.setStatus(400);
                            resp.setHeader("Content-Type", "application/json");
                            myShepherd.rollbackDBTransaction();
                            myShepherd.closeDBTransaction();
                            return;
                        } catch (NucleusException ex) {
                            JSONObject error = new JSONObject();
                            error.put("exception", ex.getMessage());
                            resp.getWriter().write(error.toString());
                            resp.setStatus(404);
                            resp.setHeader("Content-Type", "application/json");
                            myShepherd.rollbackDBTransaction();
                            myShepherd.closeDBTransaction();
                            return;
                        } catch (RuntimeException ex) {
                            // errors from the google appengine may be raised when running queries
                            JSONObject error = new JSONObject();
                            error.put("exception", ex.getMessage());
                            resp.getWriter().write(error.toString());
                            resp.setStatus(404);
                            resp.setHeader("Content-Type", "application/json");
                            myShepherd.rollbackDBTransaction();
                            myShepherd.closeDBTransaction();
                            return;
                        }
                    } else {
                        JSONObject error = new JSONObject();
                        error.put("exception",
                            "You have to log in to GET a full class list of objects.");
                        resp.getWriter().write(error.toString());
                        resp.setStatus(400);
                        resp.setHeader("Content-Type", "application/json");
                        myShepherd.rollbackDBTransaction();
                        myShepherd.closeDBTransaction();
                        return;
                    }
                }
                if (fetchParam != null) {
                    myShepherd.getPM().getFetchPlan().setGroup(fetchParam);
                }
                try {
                    myShepherd.getPM().currentTransaction().begin();
                    Object result = filterResult(myShepherd.getPM().getObjectById(id));
                    JSONObject jsonobj = convertToJson(req, result,
                        ((JDOPersistenceManager)myShepherd.getPM()).getExecutionContext(),
                        myShepherd);
                    tryCompress(req, resp, jsonobj, useCompression);
                    resp.setHeader("Content-Type", "application/json");
                } catch (NucleusObjectNotFoundException ex) {
                    resp.setContentLength(0);
                    resp.setStatus(404);
                } catch (NucleusException ex) {
                    JSONObject error = new JSONObject();
                    error.put("exception", ex.getMessage());
                    resp.getWriter().write(error.toString());
                    resp.setStatus(404);
                    resp.setHeader("Content-Type", "application/json");
                } finally {
                    if (myShepherd.getPM().currentTransaction().isActive()) {
                        myShepherd.rollbackDBTransaction();
                    }
                    myShepherd.closeDBTransaction();
                    return;
                }
            }
        } catch (JSONException e) {
            try {
                JSONObject error = new JSONObject();
                error.put("exception", e.getMessage());
                resp.getWriter().write(error.toString());
                resp.setStatus(404);
                resp.setHeader("Content-Type", "application/json");
            } catch (JSONException e1) {
                e1.printStackTrace();
            }
        }
    }

    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
    throws ServletException, IOException {
        doPost(req, resp);
    }

    protected void doOptions(HttpServletRequest req, HttpServletResponse resp)
    throws ServletException, IOException {
        ServletUtilities.doOptions(req, resp);
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
    throws ServletException, IOException {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        // getPMF(req);
        if (req.getContentLength() < 1) {
            resp.setContentLength(0);
            resp.setStatus(400); // bad request
            return;
        }
        Shepherd myShepherd = new Shepherd(req);
        myShepherd.setAction("LightRestServlet.class.POST");
        myShepherd.beginDBTransaction();

        char[] buffer = new char[req.getContentLength()];
        req.getReader().read(buffer);
        String str = new String(buffer);
        JSONObject jsonobj;
        ExecutionContext ec = ((JDOPersistenceManager)myShepherd.getPM()).getExecutionContext();
        try {
            myShepherd.beginDBTransaction();
            jsonobj = new JSONObject(str);
            String className = getNextTokenAfterSlash(req);
            jsonobj.put("class", className);

            // Process any id info provided in the URL
            AbstractClassMetaData cmd = ec.getMetaDataManager().getMetaDataForClass(className,
                ec.getClassLoaderResolver());
            String path = req.getRequestURI().substring(req.getContextPath().length() +
                req.getServletPath().length());
            StringTokenizer tokenizer = new StringTokenizer(path, "/");
            tokenizer.nextToken(); // className
            if (tokenizer.hasMoreTokens()) {
                String idToken = tokenizer.nextToken();
                Object id = RESTUtils.getIdentityForURLToken(cmd, idToken, nucCtx);
                if (id != null) {
                    if (cmd.getIdentityType() == IdentityType.APPLICATION) {
                        if (cmd.usesSingleFieldIdentityClass()) {
                            jsonobj.put(cmd.getPrimaryKeyMemberNames()[0],
                                IdentityUtils.getTargetKeyForSingleFieldIdentity(id));
                        }
                    } else if (cmd.getIdentityType() == IdentityType.DATASTORE) {
                        jsonobj.put("_id", IdentityUtils.getTargetKeyForDatastoreIdentity(id));
                    }
                }
            }
            Object pc = RESTUtils.getObjectFromJSONObject(jsonobj, className, ec);
            boolean restAccessOk = false; 

            if (restAccessOk) {
                Object obj = myShepherd.getPM().makePersistent(pc);
                JSONObject jsonobj2 = convertToJson(req, obj, ec, myShepherd);
                resp.getWriter().write(jsonobj2.toString());
                resp.setHeader("Content-Type", "application/json");
                myShepherd.getPM().currentTransaction().commit();
            } else {
                throw new NucleusUserException("Access denied"); // seems like what we should throw.  does it matter?
            }
        } catch (ClassNotResolvedException e) {
            try {
                JSONObject error = new JSONObject();
                error.put("exception", e.getMessage());
                resp.getWriter().write(error.toString());
                resp.setStatus(500);
                resp.setHeader("Content-Type", "application/json");
                LOGGER_REST.error(e.getMessage(), e);
            } catch (JSONException e1) {
                throw new RuntimeException(e1);
            }
        } catch (NucleusUserException e) {
            try {
                JSONObject error = new JSONObject();
                error.put("exception", e.getMessage());
                resp.getWriter().write(error.toString());
                resp.setStatus(400);
                resp.setHeader("Content-Type", "application/json");
                LOGGER_REST.error(e.getMessage(), e);
            } catch (JSONException e1) {
                throw new RuntimeException(e1);
            }
        } catch (NucleusException e) {
            try {
                JSONObject error = new JSONObject();
                error.put("exception", e.getMessage());
                resp.getWriter().write(error.toString());
                resp.setStatus(500);
                resp.setHeader("Content-Type", "application/json");
                LOGGER_REST.error(e.getMessage(), e);
            } catch (JSONException e1) {
                throw new RuntimeException(e1);
            }
        } catch (JSONException e) {
            try {
                JSONObject error = new JSONObject();
                error.put("exception", e.getMessage());
                resp.getWriter().write(error.toString());
                resp.setStatus(500);
                resp.setHeader("Content-Type", "application/json");
                LOGGER_REST.error(e.getMessage(), e);
            } catch (JSONException e1) {
                throw new RuntimeException(e1);
            }
        } finally {
            if (myShepherd.getPM().currentTransaction().isActive()) {
                myShepherd.rollbackDBTransaction();
            }
            myShepherd.closeDBTransaction();
        }
        resp.setStatus(201); // created
    }

    protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
    throws ServletException, IOException {
        Shepherd myShepherd = new Shepherd(req);

        myShepherd.setAction("LightRestServlet.class.Delete");
        try {
            String className = getNextTokenAfterSlash(req);
            ClassLoaderResolver clr = nucCtx.getClassLoaderResolver(
                RestServlet.class.getClassLoader());
            AbstractClassMetaData cmd = nucCtx.getMetaDataManager().getMetaDataForEntityName(
                className);
            try {
                if (cmd == null) {
                    cmd = nucCtx.getMetaDataManager().getMetaDataForClass(className, clr);
                }
            } catch (ClassNotResolvedException ex) {
                try {
                    JSONObject error = new JSONObject();
                    error.put("exception", ex.getMessage());
                    resp.getWriter().write(error.toString());
                    resp.setStatus(404);
                    resp.setHeader("Content-Type", "application/json");
                } catch (JSONException e) {
                    // will not happen
                }
                return;
            }
            Object id = getId(req);
            if (id == null) {
                // Delete all objects of this type
                myShepherd.beginDBTransaction();
                Query q = myShepherd.getPM().newQuery("SELECT FROM " + cmd.getFullClassName());
                q.deletePersistentAll();
                myShepherd.commitDBTransaction();
                q.closeAll();
            } else {
                throw new NucleusUserException("DELETE access denied");
            }
        } catch (NucleusObjectNotFoundException ex) {
            try {
                JSONObject error = new JSONObject();
                error.put("exception", ex.getMessage());
                resp.getWriter().write(error.toString());
                resp.setStatus(400);
                resp.setHeader("Content-Type", "application/json");
                return;
            } catch (JSONException e) {
                // will not happen
            }
        } catch (NucleusUserException e) {
            try {
                JSONObject error = new JSONObject();
                error.put("exception", e.getMessage());
                resp.getWriter().write(error.toString());
                resp.setStatus(400);
                resp.setHeader("Content-Type", "application/json");
                return;
            } catch (JSONException e1) {
                // ignore
            }
        } catch (NucleusException e) {
            try {
                JSONObject error = new JSONObject();
                error.put("exception", e.getMessage());
                resp.getWriter().write(error.toString());
                resp.setStatus(500);
                resp.setHeader("Content-Type", "application/json");
                LOGGER_REST.error(e.getMessage(), e);
            } catch (JSONException e1) {
                // ignore
            }
        } finally {
            if (myShepherd.getPM().currentTransaction().isActive()) {
                myShepherd.getPM().currentTransaction().rollback();
            }
            myShepherd.getPM().close();
        }
        resp.setContentLength(0);
        resp.setStatus(204); // created
    }

    boolean restAccessCheck(Object obj, HttpServletRequest req, JSONObject jsonobj) {
        System.out.println(jsonobj.toString());
        System.out.println(obj);
        System.out.println(obj.getClass());
        boolean ok = true;
        Method restAccess = null;
        try {
            restAccess = obj.getClass().getMethod("restAccess",
                new Class[] { HttpServletRequest.class, JSONObject.class });
        } catch (NoSuchMethodException nsm) {
            System.out.println("no such method??????????");
            // nothing to do
        }
        if (restAccess == null) return true; // if method doesnt exist, counts as good

        System.out.println("<<<<<<<<<< we have restAccess() on our object.... invoking!\n");
        // when .restAccess() is called, it should throw an exception to signal not allowed
        try {
            restAccess.invoke(obj, req, jsonobj);
        } catch (Exception ex) {
            ok = false;
            ex.printStackTrace();
            System.out.println("got Exception trying to invoke restAccess: " + ex.toString());
        }
        return ok;
    }

    Object filterResult(Object result)
    throws NucleusUserException {
        System.out.println("filterResult! thisRequest");
        System.out.println(thisRequest);
        Class cls = null;
        Object out = result;
        if (result instanceof Collection) {
            for (Object obj : (Collection)result) {
                cls = obj.getClass();
                if (cls.getName().equals("org.ecocean.User"))
                    throw new NucleusUserException(
                              "Cannot access org.ecocean.User objects at this time");
                else if (cls.getName().equals("org.ecocean.Role"))
                    throw new NucleusUserException(
                              "Cannot access org.ecocean.Role objects at this time");
            }
        } else {
            cls = result.getClass();
            if (cls.getName().equals("org.ecocean.User"))
                throw new NucleusUserException(
                          "Cannot access org.ecocean.User objects at this time");
            else if (cls.getName().equals("org.ecocean.Role"))
                throw new NucleusUserException(
                          "Cannot access org.ecocean.Role objects at this time");
        }
        return out;
    }

    JSONObject convertToJson(HttpServletRequest req, Object obj, ExecutionContext ec,
        Shepherd myShepherd) {
// System.out.println("convertToJson(non-Collection) trying class=" + obj.getClass());
        // System.out.println("        LightRest: convertToJson(obj) has been called!");
        boolean isEnc = (obj.getClass() == Encounter.class);

        // System.out.println("        LightRest: isEnc = "+isEnc);
        if (isEnc) {
            JSONObject jobj = getEncLightJson((Encounter)obj, req, myShepherd);
            return jobj;
        }
        JSONObject jobj = RESTUtils.getJSONObjectFromPOJO(obj, ec);

        // call decorateJson on object
        Method sj = null;
        try {
            sj = obj.getClass().getMethod("decorateJson",
                new Class[] { HttpServletRequest.class, JSONObject.class });
        } catch (NoSuchMethodException nsm) { // do nothing
            System.out.println("i guess " + obj.getClass() +
                " does not have decorateJson() method");
        }
        if (sj != null) {
            // System.out.println("trying decorateJson on "+obj.getClass());
            try {
                jobj = (JSONObject)sj.invoke(obj, req, jobj);
                // System.out.println("decorateJson result: " +jobj.toString());
            } catch (Exception ex) {
                ex.printStackTrace();
                System.out.println("got Exception trying to invoke sanitizeJson: " + ex.toString());
            }
        }
        // call sanitize Json
        sj = null;
        try {
            sj = obj.getClass().getMethod("sanitizeJson",
                new Class[] { HttpServletRequest.class, JSONObject.class });
        } catch (NoSuchMethodException nsm) { // do nothing
// System.out.println("i guess " + obj.getClass() + " does not have sanitizeJson() method");
        }
        if (sj != null) {
// System.out.println("trying sanitizeJson!");
            try {
                jobj = (JSONObject)sj.invoke(obj, req, jobj);
            } catch (Exception ex) {
                // ex.printStackTrace();
                // System.out.println("got Exception trying to invoke sanitizeJson: " + ex.toString());
            }
        }
        return jobj;
    }

    JSONArray convertToJson(HttpServletRequest req, Collection coll, ExecutionContext ec,
        Shepherd myShepherd) {
        JSONArray jarr = new JSONArray();

        for (Object o : coll) {
            if (o instanceof Collection) {
                jarr.put(convertToJson(req, (Collection)o, ec, myShepherd));
            } else { 
                jarr.put(convertToJson(req, o, ec, myShepherd));
            }
        }
        return jarr;
    }

    void tryCompress(HttpServletRequest req, HttpServletResponse resp, Object jo, boolean useComp)
    throws IOException, JSONException {
        System.out.println("??? TRY COMPRESS ??");
        // String s = scrubJson(req, jo).toString();
        String s = jo.toString();
        if (!useComp || (s.length() < 3000)) { // kinda guessing on size here, probably doesnt matter
            resp.getWriter().write(s);
        } else {
            resp.setHeader("Content-Encoding", "gzip");
            OutputStream o = resp.getOutputStream();
            GZIPOutputStream gz = new GZIPOutputStream(o);
            gz.write(s.getBytes());
            gz.flush();
            gz.close();
            o.close();
        }
    }

    private JSONObject getEncLightJson(Encounter enc, HttpServletRequest req, Shepherd myShepherd) {
        String context = ServletUtilities.getContext(req);

        if ((CommonConfiguration.getProperty("collaborationSecurityEnabled",
            context) != null) && (CommonConfiguration.getProperty("collaborationSecurityEnabled",
                context).equals("true")) &&
            !Collaboration.canUserViewOwnedObject(enc.getSubmitterID(), req, myShepherd))
            return null;
        // would be time to check for viewing permissions

        JSONObject jobj = new JSONObject();
        for (String fieldName : Encounter_Light_Str_Fields) {
            try {
                Method getter = Encounter.class.getMethod(getterName(fieldName));
                String val = (String)getter.invoke(enc);
                if (val == null) continue;
                jobj.put(fieldName, val);
            } catch (NoSuchMethodException nsm) { // lets not stacktrace on this
                System.out.println(
                    "WARNING: LightRestServlet.getEncLightJson() finds no property '" + fieldName +
                    "' on Encounter; ignoring");
            } catch (Exception e) {
                System.out.println("Exception on LightRestServlet.getEncLightJson for fieldName " +
                    fieldName);
                e.printStackTrace();
            }
        }
        for (String fieldName : Encounter_Light_Int_Fields) {
            try {
                Method getter = Encounter.class.getMethod(getterName(fieldName));
                int val = ((Integer)getter.invoke(enc)).intValue();
                jobj.put(fieldName, val);
            } catch (Exception e) {
                System.out.println("Exception on LightRestServlet.getEncLightJson for fieldName " +
                    fieldName);
                e.printStackTrace();
            }
        }
        for (String fieldName : Encounter_Light_Long_Fields) {
            try {
                Method getter = Encounter.class.getMethod(getterName(fieldName));
                long val = ((Long)getter.invoke(enc)).longValue();
                jobj.put(fieldName, val);
            } catch (Exception e) {
                System.out.println("Exception on LightRestServlet.getEncLightJson for fieldName " +
                    fieldName);
                e.printStackTrace();
            }
        }
        // add the individual's display name if it has one
        if (enc.getIndividual() != null && Util.stringExists(enc.getIndividual().getDisplayName(req,
            myShepherd))) {
            try {
                jobj.put("displayName", enc.getIndividual().getDisplayName(req, myShepherd));
            } catch (org.datanucleus.api.rest.orgjson.JSONException je) {
                je.printStackTrace();
            }
        }
        return jobj;
    }

    private String getterName(String fieldName) {
        return ("get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1));
    }
}
