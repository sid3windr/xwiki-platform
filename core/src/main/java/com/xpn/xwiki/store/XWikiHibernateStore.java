/*
 * Copyright 2006, XpertNet SARL, and individual contributors as indicated
 * by the contributors.txt.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 *
 * @author ludovic
 * @author namphunghai
 * @author erwan
 * @author jeremi
 * @author sdumitriu
 * @author thomas
 */
package com.xpn.xwiki.store;


import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.XWikiLink;
import com.xpn.xwiki.doc.XWikiLock;
import com.xpn.xwiki.monitor.api.MonitorPlugin;
import com.xpn.xwiki.objects.*;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.objects.classes.StringClass;
import com.xpn.xwiki.objects.classes.TextAreaClass;
import com.xpn.xwiki.render.XWikiRenderer;
import com.xpn.xwiki.stats.impl.XWikiStats;
import com.xpn.xwiki.util.Util;
import com.xpn.xwiki.web.Utils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.*;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Settings;
import org.hibernate.connection.ConnectionProvider;
import org.hibernate.impl.SessionFactoryImpl;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;

import java.io.BufferedReader;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;


public class XWikiHibernateStore extends XWikiHibernateBaseStore implements XWikiStoreInterface {

    private static final Log log = LogFactory.getLog(XWikiHibernateStore.class);
    private Map validTypesMap = new HashMap();

    /**
     * THis allows to initialize our storage engine.
     * The hibernate config file path is taken from xwiki.cfg
     * or directly in the WEB-INF directory.
     * @param xwiki
     * @param context
     */
    public XWikiHibernateStore(XWiki xwiki, XWikiContext context) {
        super(xwiki, context);
        initValidColumTypes();
    }

    /**
     * Initialize the storage engine with a specific path
     * This is used for tests.
     * @param hibpath
     */
    public XWikiHibernateStore(String hibpath) {
        super(hibpath);
        initValidColumTypes();
    }

    /**
     * This initializes the valid custom types
     * Used for Custom Mapping
     */
    private void initValidColumTypes() {
        String[] string_types = { "string" , "text" , "clob" };
        String[] number_types = { "integer" , "long" , "float", "double", "big_decimal", "big_integer", "yes_no", "true_false" };
        String[] date_types = { "date" , "time" , "timestamp" };
        String[] boolean_types = { "boolean" , "yes_no" , "true_false", "integer" };
        validTypesMap = new HashMap();
        validTypesMap.put("com.xpn.xwiki.objects.classes.StringClass" , string_types);
        validTypesMap.put("com.xpn.xwiki.objects.classes.TextAreaClass" , string_types);
        validTypesMap.put("com.xpn.xwiki.objects.classes.PasswordClass" , string_types);
        validTypesMap.put("com.xpn.xwiki.objects.classes.NumberClass" , number_types);
        validTypesMap.put("com.xpn.xwiki.objects.classes.DateClass" , date_types);
        validTypesMap.put("com.xpn.xwiki.objects.classes.BooleanClass" , boolean_types);
    }


    /**
     * Allows to create a new wiki database
     * and initialize the default tables
     * @param wikiName
     * @param context
     * @throws XWikiException
     */
    public void createWiki(String wikiName, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        String database = context.getDatabase();
        Statement stmt = null;
        try {
            bTransaction = beginTransaction(context);
            Session session = getSession(context);
            Connection connection = session.connection();
            stmt = connection.createStatement();
            stmt.execute("create database " + wikiName);
            endTransaction(context, true);
        }
        catch (Exception e) {
            Object[] args = { wikiName  };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CREATE_DATABASE,
                    "Exception while create wiki database {0}", e, args);
        } finally {
            context.setDatabase(database);
            try {
                if (stmt!=null)
                    stmt.close();
            } catch (Exception e) {}
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    /**
     * Verifies if a wiki document exists
     * @param doc
     * @param context
     * @return
     * @throws XWikiException
     */
    public boolean exists(XWikiDocument doc, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        MonitorPlugin monitor  = Util.getMonitorPlugin(context);
        try {

            doc.setStore(this);
            checkHibernate(context);

            // Start monitoring timer
            if (monitor!=null)
                monitor.startTimer("hibernate");

            bTransaction = bTransaction && beginTransaction(false, context);
            Session session = getSession(context);
            String fullName = doc.getFullName();

            String sql = "select doc.fullName from XWikiDocument as doc where doc.fullName=:fullName";
            if (monitor!=null)
                monitor.setTimerDesc("hibernate", sql);
            Query query = session.createQuery(sql);
            query.setString("fullName", fullName);
            Iterator it = query.list().iterator();
            while (it.hasNext()) {
                if (fullName.equals(it.next()))
                    return true;
            }
            return false;
        } catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CHECK_EXISTS_DOC,
                    "Exception while reading document {0}", e, args);
        } finally {
            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");

            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}
        }
    }

    public void saveXWikiDoc(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException {
        MonitorPlugin monitor  = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
                monitor.startTimer("hibernate");
            doc.setStore(this);
            // Make sure the database name is stored
            doc.setDatabase(context.getDatabase());

            if (bTransaction) {
                checkHibernate(context);

                SessionFactory sfactory = injectCustomMappingsInSessionFactory(doc, context);
                bTransaction = beginTransaction(sfactory, context);
            }
            Session session = getSession(context);
            session.setFlushMode(FlushMode.COMMIT);

            // These informations will allow to not look for attachments and objects on loading
            doc.setElement(XWikiDocument.HAS_ATTACHMENTS, (doc.getAttachmentList().size()!=0));
            doc.setElement(XWikiDocument.HAS_OBJECTS, (doc.getxWikiObjects().size()!=0));

            // Let's update the class XML since this is the new way to store it
            BaseClass bclass = doc.getxWikiClass();
            if ((bclass!=null)&&(bclass.getFieldList().size()>0)) {
               doc.setxWikiClassXML(bclass.toXMLString());
            }

            if (doc.hasElement(XWikiDocument.HAS_ATTACHMENTS))
             saveAttachmentList(doc, context, false);

            // Handle the latest text file
            if (doc.isContentDirty()||doc.isMetaDataDirty()) {
                Date ndate = new Date();
                doc.setDate(ndate);
                if (doc.isContentDirty()) {
                    doc.setContentUpdateDate(ndate);
                    doc.setContentAuthor(doc.getAuthor());
                }
                doc.incrementVersion();
                if (context.getWiki().hasVersioning(doc.getFullName(), context))
                 context.getWiki().getVersioningStore().updateXWikiDocArchive(doc, doc.toXML(context), false, context);
            } else {
                if (doc.getDocumentArchive()!=null) {
                    // Let's make sure we save the archive if we have one
                    // This is especially needed if we load a document from XML
                    if (context.getWiki().hasVersioning(doc.getFullName(), context))
                     context.getWiki().getVersioningStore().saveXWikiDocArchive(doc.getDocumentArchive(),false, context);
                } else {
                    // Make sure the getArchive call has been made once
                    // with a valid context
                    try {
                        if (context.getWiki().hasVersioning(doc.getFullName(), context))
                         doc.getDocumentArchive(context);
                    } catch (XWikiException e) {
                        // this is a non critical error
                    }
                }
            }

            // Verify if the document already exists
            Query query = session.createQuery("select xwikidoc.id from XWikiDocument as xwikidoc where xwikidoc.id = :id");
            query.setLong("id", doc.getId());
            if (query.uniqueResult()==null)
                session.save(doc);
            else
                session.update(doc);

            // Remove properties planned for removal
            if (doc.getObjectsToRemove().size()>0) {
                for (int i=0;i<doc.getObjectsToRemove().size();i++) {
                    BaseObject bobj = (BaseObject)doc.getObjectsToRemove().get(i);
                    if (bobj!=null)
                     deleteXWikiObject(bobj, context, false);
                }
                doc.setObjectsToRemove(new ArrayList());
            }

            // We should only save the class if we are using the class table mode
            if (bclass!=null) {
                bclass.setName(doc.getFullName());
                if ((bclass.getFieldList().size()>0)&&(useClassesTable(true, context)))
                    saveXWikiClass(bclass, context, false);
            } else {
                // TODO: Remove existing class
            }

            if (doc.hasElement(XWikiDocument.HAS_OBJECTS)) {
                // TODO: Delete all objects for which we don't have a name in the Map..
                Iterator it = doc.getxWikiObjects().values().iterator();
                while (it.hasNext()) {
                    Vector objects = (Vector) it.next();
                    for (int i=0;i<objects.size();i++) {
                        BaseCollection obj = (BaseCollection)objects.get(i);
                        if (obj!=null){
                            obj.setName(doc.getFullName());
                            saveXWikiCollection(obj, context, false);
                        }
                    }
                }
            }


            if (context.getWiki().hasBacklinks(context)){
                saveLinks(doc, context, true);
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
            doc.setNew(false);
        } catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_DOC,
                    "Exception while saving document {0}", e, args);
        } finally {
            try {
                if (bTransaction) {
                    endTransaction(context, false);
                }
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public void saveXWikiDoc(XWikiDocument doc, XWikiContext context) throws XWikiException {
        saveXWikiDoc(doc, context, true);
    }

    public XWikiDocument loadXWikiDoc(XWikiDocument doc, XWikiContext context) throws XWikiException {
        //To change body of implemented methods use Options | File Templates.
        boolean bTransaction = true;
        MonitorPlugin monitor = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
                monitor.startTimer("hibernate");
            doc.setStore(this);
            checkHibernate(context);

            SessionFactory sfactory = injectCustomMappingsInSessionFactory(doc, context);
            bTransaction = bTransaction && beginTransaction(sfactory, false, context);
            Session session = getSession(context);
            session.setFlushMode(FlushMode.NEVER);

            try {
                session.load(doc, new Long(doc.getId()));
                doc.setDatabase(context.getDatabase());
                doc.setNew(false);
            } catch (ObjectNotFoundException e)
            { // No document
                doc.setNew(true);
                return doc;
            }

            // Loading the attachment list
            if (doc.hasElement(XWikiDocument.HAS_ATTACHMENTS))
                loadAttachmentList(doc, context, false);

            // TODO: handle the case where there are no xWikiClass and xWikiObject in the Database
            BaseClass bclass = new BaseClass();
            String cxml = doc.getxWikiClassXML();
            if (cxml!=null) {
                bclass.fromXML(cxml);
                bclass.setName(doc.getFullName());
                doc.setxWikiClass(bclass);
            } else if (useClassesTable(false, context)) {
                bclass.setName(doc.getFullName());
                bclass = loadXWikiClass(bclass, context, false);
                doc.setxWikiClass(bclass);
            }

            // Store this XWikiClass in the context so that we can use it in case of recursive usage of classes
            context.addBaseClass(bclass);


            if (doc.hasElement(XWikiDocument.HAS_OBJECTS)) {
                Query query;
                query = session.createQuery("from BaseObject as bobject where bobject.name = :name order by bobject.number");
                query.setText("name", doc.getFullName());
                Iterator it = query.list().iterator();

                boolean hasGroups = false;
                while (it.hasNext()) {
                    BaseObject object = (BaseObject) it.next();
                    String className = object.getClassName();
                    // We use the internal class to store the statistics
                    if (className.equals("internal"))
                        continue;

                    if (className.equals("XWiki.XWikiGroups")) {
                        hasGroups = true;
                        continue;
                    }

                    // It seems to search before is case insensitive
                    // And this would break the loading if we get an
                    // object which doesn't really belong to this document
                    if (!object.getName().equals(doc.getFullName()))
                        continue;

                    if (!className.equals("")) {
                        BaseObject newobject;
                        if (className.equals(doc.getFullName()))
                            newobject = bclass.newCustomClassInstance(context);
                        else
                            newobject = BaseClass.newCustomClassInstance(object.getClassName(), context);
                        if (newobject!=null) {
                            newobject.setId(object.getId());
                            newobject.setClassName(object.getClassName());
                            newobject.setName(object.getName());
                            newobject.setNumber(object.getNumber());
                            object = newobject;
                        }
                        loadXWikiCollection(object, doc, context, false, true);
                        doc.setObject(className, object.getNumber(), object);
                    }
                }

                if (hasGroups) {
                    Query query2;
                    query2 = session.createQuery("select bobject.number, prop.value from StringProperty as prop, BaseObject as bobject where bobject.name = :name and bobject.className='XWiki.XWikiGroups' and bobject.id=prop.id.id and prop.id.name='member' order by bobject.number");
                    query2.setText("name", doc.getFullName());
                    Iterator it2 = query2.list().iterator();
                    while (it2.hasNext()) {
                        Object[] result = (Object[])it2.next();
                        Integer number = (Integer)result[0];
                        String member = (String)result[1];
                        BaseObject obj = BaseClass.newCustomClassInstance("XWiki.XWikiGroups", context);
                        obj.setName(doc.getFullName());
                        obj.setClassName("XWiki.XWikiGroups");
                        obj.setNumber(number.intValue());
                        obj.setStringValue("member", member);
                        doc.setObject("XWiki.XWikiGroups", obj.getNumber(), obj);
                    }
                }
            }
            if (bTransaction)
                endTransaction(context, false, false);
        } catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_READING_DOC,
                    "Exception while reading document {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
        return doc;
    }


    private MonitorPlugin getMonitorPlugin(XWikiContext context) {
        try {
            if ((context==null)||(context.getWiki()==null))
                return null;

            return (MonitorPlugin) context.getWiki().getPlugin("monitor", context);
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteXWikiDoc(XWikiDocument doc, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        MonitorPlugin monitor  = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
                monitor.startTimer("hibernate");
            checkHibernate(context);
            SessionFactory sfactory = injectCustomMappingsInSessionFactory(doc, context);
            bTransaction = bTransaction && beginTransaction(sfactory, context);
            Session session = getSession(context);
            session.setFlushMode(FlushMode.COMMIT);

            if (doc.getStore()==null) {
                Object[] args = { doc.getFullName() };
                throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_CANNOT_DELETE_UNLOADED_DOC,
                        "Impossible to delete document {0} if it is not loaded", null, args);
            }

            // Let's delete any attachment this document might have
            List attachlist = doc.getAttachmentList();
            for (int i=0;i<attachlist.size();i++) {
                XWikiAttachment attachment = (XWikiAttachment) attachlist.get(i);
                context.getWiki().getAttachmentStore().deleteXWikiAttachment(attachment, false, context, false);
            }

            // deleting XWikiLinks
            if (context.getWiki().hasBacklinks(context)){
                deleteLinks(doc.getId(),context,true);
            }

            BaseClass bclass = doc.getxWikiClass();
            if ((bclass.getFieldList().size()>0)&&(useClassesTable(true, context))) {
                deleteXWikiClass(bclass, context, false);
            }

            // Find the list of classes for which we have an object
            // Remove properties planned for removal
            if (doc.getObjectsToRemove().size()>0) {
                for (int i=0;i<doc.getObjectsToRemove().size();i++) {
                    BaseObject bobj = (BaseObject)doc.getObjectsToRemove().get(i);
                    if (bobj!=null)
                     deleteXWikiObject(bobj, context, false);
                }
                doc.setObjectsToRemove(new ArrayList());
            }
            Iterator it = doc.getxWikiObjects().values().iterator();
            while (it.hasNext()) {
                Vector objects = (Vector) it.next();
                for (int i=0;i<objects.size();i++) {
                    BaseObject obj = (BaseObject)objects.get(i);
                    if (obj!=null)
                        deleteXWikiObject(obj, context, false);
                }
            }

            session.delete(doc);
            if (bTransaction)
                endTransaction(context, true);
        } catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_DOC,
                    "Exception while deleting document {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public void saveXWikiObject(BaseObject object, XWikiContext context, boolean bTransaction) throws XWikiException {
        saveXWikiCollection(object, context, bTransaction);
    }

    public void saveXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (object==null)
                return;
            // We need a slightly different behavior here
            boolean stats = (object instanceof XWikiStats);

            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            // Verify if the property already exists
            Query query;
            if (stats)
                query = session.createQuery("select obj.id from " +
                        object.getClass().getName() + " as obj where obj.id = :id");
            else
                query = session.createQuery("select obj.id from BaseObject as obj where obj.id = :id");
            query.setInteger("id", object.getId());
            if (query.uniqueResult()==null) {
                if (stats)
                 session.save(object);
                else
                 session.save((String)"com.xpn.xwiki.objects.BaseObject", (Object)object);
            }
            else {
                if (stats)
                 session.update(object);
                else
                 session.update((String)"com.xpn.xwiki.objects.BaseObject", (Object)object);
            }
/*
            if (stats)
             session.saveOrUpdate(object);
            else
             session.saveOrUpdate((String)"com.xpn.xwiki.objects.BaseObject", (Object)object);
*/
            BaseClass bclass = object.getxWikiClass(context);
            List handledProps = new ArrayList();
            if ((bclass!=null)&&(bclass.hasCustomMapping())&&context.getWiki().hasCustomMappings()) {
                // save object using the custom mapping
                Map objmap = object.getCustomMappingMap();
                handledProps = bclass.getCustomMappingPropertyList(context);
                Session dynamicSession = session.getSession(EntityMode.MAP);
                query = session.createQuery("select obj.id from " + bclass.getName() + " as obj where obj.id = :id");
                query.setInteger("id", object.getId());
                if (query.uniqueResult()==null)
                    dynamicSession.save((String) bclass.getName(), objmap);
                else
                    dynamicSession.update((String) bclass.getName(), objmap);

                // dynamicSession.saveOrUpdate((String) bclass.getName(), objmap);
            }

            if (!object.getClassName().equals("internal")) {
                // Remove all existing properties
                if (object.getFieldsToRemove().size()>0) {
                    for (int i=0;i<object.getFieldsToRemove().size();i++) {
                        BaseProperty prop = (BaseProperty) object.getFieldsToRemove().get(i);
                        if (!handledProps.contains(prop.getName()))
                            session.delete(prop);
                    }
                    object.setFieldsToRemove(new ArrayList());
                }

                Iterator it = object.getPropertyList().iterator();
                while (it.hasNext()) {
                    String key = (String) it.next();
                    BaseProperty prop = (BaseProperty) object.getField(key);
                    if (!prop.getName().equals(key)) {
                        Object[] args = { key, object.getName() };
                        throw new XWikiException(XWikiException.MODULE_XWIKI_CLASSES, XWikiException.ERROR_XWIKI_CLASSES_FIELD_INVALID,
                                "Field {0} in object {1} has an invalid name", null, args);
                    }

                    String pname = prop.getName() ;
                    if(pname != null && !pname.trim().equals("") && !handledProps.contains(pname) ){
                       saveXWikiProperty(prop, context, false);
                    }
                }
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (XWikiException xe) {
            throw xe;
        } catch (Exception e) {
            Object[] args = { object.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_OBJECT,
                    "Exception while saving object {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, true);
            } catch (Exception e) {}
        }
    }

    public void loadXWikiObject(BaseObject object, XWikiContext context, boolean bTransaction) throws XWikiException {
        loadXWikiCollection(object, null,context, bTransaction, false);
    }

    public void loadXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction) throws XWikiException {
        loadXWikiCollection(object, null, context, bTransaction, false);
    }

    public void loadXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction, boolean alreadyLoaded) throws XWikiException {
        loadXWikiCollection(object, null, context, bTransaction, alreadyLoaded);
    }

    public void loadXWikiCollection(BaseCollection object1, XWikiDocument doc, XWikiContext context, boolean bTransaction, boolean alreadyLoaded) throws XWikiException {
        BaseCollection object = object1;
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            if (!alreadyLoaded) {
                try {
                    session.load(object, new Integer(object1.getId()));
                }
                catch (ObjectNotFoundException e) {
                    // There is no object data saved
                    object = null;
                    return;
                }
            }

            String className = object.getClassName();
            BaseClass bclass = null;
            if (!className.equals(object.getName())) {
                // Let's check if the class has a custom mapping
                bclass = object.getxWikiClass(context);
            } else {
                // We need to get it from the document otherwise
                // we will go in an endless loop
                if (doc!=null)
                    bclass = doc.getxWikiClass();
            }

            List handledProps = new ArrayList();
            try {
                if ((bclass!=null)&&(bclass.hasCustomMapping())&&context.getWiki().hasCustomMappings()) {
                    Session dynamicSession = session.getSession(EntityMode.MAP);
                    Object map = dynamicSession.load((String) bclass.getName(),new Integer(object.getId()));
                    // Let's make sure to look for null fields in the dynamic mapping
                    bclass.fromValueMap((Map)map, object);
                    handledProps = bclass.getCustomMappingPropertyList(context);
                    for (Iterator it = handledProps.iterator();it.hasNext();) {
                        String prop = (String)it.next();
                        if (((Map)map).get(prop)==null)
                            handledProps.remove(prop);
                    }
                }
            } catch (Exception e) {}

            // Load strings, integers, dates all at once


            if (!className.equals("internal")) {
                HashMap map = new HashMap();
                Query query = session.createQuery("select prop.name, prop.classType from BaseProperty as prop where prop.id.id = :id");
                query.setInteger("id", object.getId());
                List list = query.list();
                Iterator it = list.iterator();
                while (it.hasNext()) {
                    Object obj = it.next();
                    Object[] result = (Object[]) obj;
                    String name = (String)result[0];
                    // No need to load fields already loaded from
                    // custom mapping
                    if (handledProps.contains(name))
                      continue;
                    String classType = (String)result[1];
                    BaseProperty property = null;

                    try {
                        property = (BaseProperty) Class.forName(classType).newInstance();
                        property.setObject(object);
                        property.setName(name);
                        loadXWikiProperty(property, context, false);
                    } catch (Exception e) {
                        // WORKAROUND IN CASE OF MIXMATCH BETWEEN STRING AND LARGESTRING
                        try {
                            if (property instanceof StringProperty) {
                                LargeStringProperty property2 = new LargeStringProperty();
                                property2.setObject(object);
                                property2.setName(name);
                                loadXWikiProperty(property2, context, false);
                                property.setValue(property2.getValue());

                                if (bclass!=null) {
                                    if (bclass.get(name) instanceof TextAreaClass)
                                     property = property2;
                                }

                            } else if (property instanceof LargeStringProperty) {
                                StringProperty property2 = new StringProperty();
                                property2.setObject(object);
                                property2.setName(name);
                                loadXWikiProperty(property2, context, false);
                                property.setValue(property2.getValue());

                                if (bclass!=null) {
                                    if (bclass.get(name) instanceof StringClass)
                                     property = property2;
                                }
                            } else
                                throw e;
                        } catch (Throwable e2) {
                            throw e;
                        }
                    }

                    object.addField(name, property);
                }
            }
            if (bTransaction) {
                endTransaction(context, false, false);
            }
        } catch (Exception e) {
            Object[] args = { object.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                    "Exception while loading object {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}
        }

    }

    public void deleteXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction) throws XWikiException {
        deleteXWikiCollection(object, context, bTransaction, false);
    }

    public void deleteXWikiCollection(BaseCollection object, XWikiContext context, boolean bTransaction, boolean evict) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            // Let's check if the class has a custom mapping
            BaseClass bclass = object.getxWikiClass(context);
            List handledProps = new ArrayList();
            if ((bclass!=null)&&(bclass.hasCustomMapping())&&context.getWiki().hasCustomMappings()) {
                handledProps = bclass.getCustomMappingPropertyList(context);
                Session dynamicSession = session.getSession(EntityMode.MAP);
                Object map = dynamicSession.get((String) bclass.getName(),new Integer(object.getId()));
                if (map!=null) {
                    if (evict)
                        dynamicSession.evict(map);
                    dynamicSession.delete((Object) map);
                }
            }

            if (!object.getClassName().equals("internal")) {
                for (Iterator it = object.getFieldList().iterator(); it.hasNext();) {
                    BaseElement property = (BaseElement) it.next();
                    if (!handledProps.contains(property.getName())) {
                        if (evict)
                            session.evict(property);
                        if (session.get(property.getClass(), property)!=null)
                            session.delete(property);
                    }
                }
            }

            // In case of custom class we need to force it as BaseObject
            // to delete the xwikiobject row
            if (!"".equals(bclass.getCustomClass())) {
                BaseObject cobject = new BaseObject();
                cobject.setName(object.getName());
                cobject.setClassName(object.getClassName());
                cobject.setNumber(object.getNumber());
                cobject.setId(object.getId());
                if (evict)
                    session.evict(cobject);
                session.delete(cobject);
            } else {
                if (evict)
                    session.evict(object);
                session.delete(object);
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            Object[] args = { object.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_OBJECT,
                    "Exception while deleting object {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void deleteXWikiObject(BaseObject baseObject, XWikiContext context, boolean bTransaction, boolean bEvict) throws XWikiException {
        deleteXWikiCollection(baseObject, context, bTransaction, bEvict);
    }

    public void deleteXWikiObject(BaseObject baseObject, XWikiContext context, boolean b) throws XWikiException {
        deleteXWikiCollection(baseObject, context, b);
    }

    public void deleteXWikiClass(BaseClass baseClass, XWikiContext context, boolean b) throws XWikiException {
        deleteXWikiCollection(baseClass, context, b);
    }


    public void loadXWikiProperty(PropertyInterface property, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);


            session.load(property, (Serializable) property);

            // TODO: understand why collections are lazy loaded
            // Let's force reading lists if there is a list
            // This seems to be an issue since Hibernate 3.0
            // Without this test ViewEditTest.testUpdateAdvanceObjectProp fails
            if (property instanceof ListProperty) {
                ((ListProperty)property).getList();
            }

            if (bTransaction) {
                endTransaction(context, false, false);
            }
        }
        catch (Exception e) {
            BaseCollection obj = property.getObject();
            Object[] args = { (obj!=null) ? obj.getName() : "unknown", property.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                    "Exception while loading property {1} of object {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}
        }
    }


    public void saveXWikiProperty(PropertyInterface property, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);
            Query query = session.createQuery("select prop.name from BaseProperty as prop where prop.id.id = :id and prop.id.name= :name");
            query.setInteger("id", property.getId());
            query.setString("name", property.getName());
            if (query.uniqueResult()==null) {
                session.save(property);
            }
            else {
                session.update(property);
            }

/*
// I'm using a local transaction
// There might be implications to this for a wider transaction
            Transaction ltransaction = session.beginTransaction();

// Use to chose what to delete
            boolean isSave = false;
            try
            {
                Query query = session.createQuery("select prop.name from BaseProperty as prop where prop.id.id = :id and prop.id.name= :name");
                query.setInteger("id", property.getId());
                query.setString("name", property.getName());
                if (query.uniqueResult()==null) {
                    isSave = true;
                    session.save(property);
                }
                else {
                    isSave = false;
                    session.update(property);
                }
                session.flush();
                ltransaction.commit();
            } catch (Exception e) {
// We can't clean-up ListProperties
                if (property instanceof ListProperty)
                    throw e;

// This seems to have failed..
// This is an attempt to cleanup a potential mess
// This code is only called if the tables are in an incoherent state
// (Example: data in xwikiproperties and no data in xwikiintegers or vice-versa)
// TODO: verify of the code works with longer transactions
                BaseProperty prop2;
// Depending on save/update there is too much data either
// in the BaseProperty table or in the inheritated property table
// We need to delete this data
                if (isSave)
                    prop2 = (BaseProperty) property;
                else
                    prop2 = new BaseProperty();

                prop2.setName(property.getName());
                prop2.setObject(property.getObject());
                ltransaction.rollback();

// We need to run the delete in a separate session
// This is not a problem since this is cleaning up
                Session session2 = getSessionFactory().openSession();
                Transaction transaction2 = session2.beginTransaction();
                session2.delete(prop2);
                session2.flush();

// I don't understand why I can't run this in the general session
// This might make transactions fail
                if (!isSave)
                    session2.save(property);
                transaction2.commit();
                session2.close();
            }
*/
            if (bTransaction)
                endTransaction(context, true);
        }
        catch (Exception e) {
            BaseCollection obj = property.getObject();
            Object[] args = { (obj!=null) ? obj.getName() : "unknown", property.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_OBJECT,
                    "Exception while saving property {1} of object {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void saveXWikiClass(BaseClass bclass, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            // Verify if the property already exists
            Query query = session.createQuery("select obj.id from BaseClass as obj where obj.id = :id");
            query.setInteger("id", bclass.getId());
            if (query.uniqueResult()==null)
                session.save(bclass);
            else
                session.update(bclass);

            // Remove all existing properties
            if (bclass.getFieldsToRemove().size()>0) {
                for (int i=0;i<bclass.getFieldsToRemove().size();i++) {
                    session.delete(bclass.getFieldsToRemove().get(i));
                }
                bclass.setFieldsToRemove(new ArrayList());
            }


            Collection coll = bclass.getFieldList();
            Iterator it = coll.iterator();
            while (it.hasNext()) {
                PropertyClass prop = (PropertyClass) it.next();
                String pname = prop.getName();
                if(pname != null && !pname.trim().equals("") ) {
                    saveXWikiClassProperty(prop, context, false);
                }
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        } catch (Exception e) {
            Object[] args = { bclass.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_CLASS,
                    "Exception while saving class {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public BaseClass loadXWikiClass(BaseClass bclass, XWikiContext context) throws XWikiException {
        return loadXWikiClass(bclass, context, true);
    }

    public BaseClass loadXWikiClass(BaseClass bclass, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            try {
                session.load(bclass, new Integer(bclass.getId()));

                HashMap map = new HashMap();
                Query query = session.createQuery("select prop.name, prop.classType from PropertyClass as prop where prop.id.id = :id order by prop.number asc");
                query.setInteger("id", bclass.getId());
                Iterator it = query.list().iterator();
                while (it.hasNext()) {
                    Object obj = it.next();
                    Object[] result = (Object[]) obj;
                    String name = (String)result[0];
                    String classType = (String)result[1];
                    PropertyClass property = (PropertyClass) Class.forName(classType).newInstance();
                    property.setName(name);
                    property.setObject(bclass);
                    session.load(property, property);
                    bclass.addField(name, property);
                }
            }
            catch (ObjectNotFoundException e) {
            }

            if (bTransaction) {
                endTransaction(context, false, false);
            }

            if ((bclass!=null)&&(bclass.hasExternalCustomMapping()))
                setSessionFactory(injectCustomMappingsInSessionFactory(bclass, context));

            return bclass;
        } catch (Exception e) {
            Object[] args = { bclass.getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_CLASS,
                    "Exception while loading class {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}
        }
    }

    public void saveXWikiClassProperty(PropertyClass property, XWikiContext context, boolean bTransaction) throws XWikiException
    {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


// I'm using a local transaction
// There might be implications to this for a wider transaction
            Transaction ltransaction = session.beginTransaction();

// Use to chose what to delete
            boolean isSave = false;
            try
            {
                Query query = session.createQuery("select prop.name from PropertyClass as prop where prop.id.id = :id and prop.id.name= :name");
                query.setInteger("id", property.getId());
                query.setString("name", property.getName());
                if (query.uniqueResult()==null) {
                    isSave = true;
                    session.save(property);
                }
                else {
                    isSave = false;
                    session.update(property);
                }

                session.flush();
                ltransaction.commit();
            } catch (Exception e) {
// This seems to have failed..
// This is an attempt to cleanup a potential mess
// This code is only called if the tables are in an incoherent state
// (Example: data in xwikiproperties and no data in xwikiintegers or vice-versa)
// TODO: verify of the code works with longer transactions
                PropertyClass prop2;
// Depending on save/update there is too much data either
// in the BaseProperty table or in the inheritated property table
// We need to delete this data
                if (isSave)
                    prop2 = (PropertyClass) property;
                else
                    prop2 = new PropertyClass();

                prop2.setName(property.getName());
                prop2.setObject(property.getObject());
                ltransaction.rollback();

// We need to run the delete in a separate session
// This is not a problem since this is cleaning up
                Session session2 = getSessionFactory().openSession();
                Transaction transaction2 = session2.beginTransaction();
                session2.delete(prop2);
                session2.flush();

// I don't understand why I can't run this in the general session
// This might make transactions fail
                if (!isSave)
                    session2.save(property);
                transaction2.commit();
                session2.close();
            }

            if (bTransaction)
                endTransaction(context, true);

        }
        catch (Exception e) {
            Object[] args = { property.getObject().getName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_CLASS,
                    "Exception while saving class {0}", e, args);

        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void loadAttachmentList(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("from XWikiAttachment as attach where attach.docId=:docid");
            query.setLong("docid", doc.getId());
            List list = query.list();
            for (int i=0;i<list.size();i++) {
                ((XWikiAttachment)list.get(i)).setDoc(doc);
            }
            doc.setAttachmentList(list);
            if (bTransaction)
                endTransaction(context, false, false);
        }
        catch (Exception e) {
            e.printStackTrace();
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCHING_ATTACHMENT,
                    "Exception while searching attachments for documents {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}
        }
    }

    public void saveAttachmentList(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            List list = doc.getAttachmentList();
            for (int i=0;i<list.size();i++) {
                XWikiAttachment attachment = (XWikiAttachment) list.get(i);
                attachment.setDoc(doc);
                saveAttachment(attachment, false, context, false);
            }

            if (bTransaction)
                endTransaction(context, true);
        }
        catch (Exception e) {
            Object[] args = { doc.getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_ATTACHMENT_LIST,
                    "Exception while saving attachments attachment list of document {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void saveAttachment(XWikiAttachment attachment, XWikiContext context, boolean bTransaction) throws XWikiException {
        saveAttachment(attachment, true, context, bTransaction);
    }

    public void saveAttachment(XWikiAttachment attachment, boolean parentUpdate, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);


            Query query = session.createQuery("select attach.id from XWikiAttachment as attach where attach.id = :id");
            query.setLong("id", attachment.getId());
            if (query.uniqueResult()==null)
                session.save(attachment);
            else
                session.update(attachment);

            if (parentUpdate)
                context.getWiki().getStore().saveXWikiDoc(attachment.getDoc(), context, false);
            if (bTransaction) {
                endTransaction(context, true);
            }
        }
        catch (Exception e) {
            Object[] args = { attachment.getFilename(), attachment.getDoc().getFullName() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_ATTACHMENT,
                    "Exception while saving attachments for attachment {0} of document {1}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public XWikiLock loadLock(long docId, XWikiContext context, boolean bTransaction) throws XWikiException {
        XWikiLock lock=null;
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("select lock.docId from XWikiLock as lock where lock.docId = :docId");
            query.setLong("docId", docId);
            if (query.uniqueResult()!=null)
            {
                lock = new XWikiLock();
                session.load(lock, new Long(docId));
            }

            if (bTransaction)
                endTransaction(context, false, false);
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_LOCK,
                    "Exception while loading lock", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}
        }
        return lock;
    }

    public void saveLock(XWikiLock lock, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            Query query = session.createQuery("select lock.docId from XWikiLock as lock where lock.docId = :docId");
            query.setLong("docId", lock.getDocId());
            if (query.uniqueResult()==null)
                session.save(lock);
            else
                session.update(lock);

            if (bTransaction) {
                endTransaction(context, true);
            }
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_LOCK,
                    "Exception while locking document", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void deleteLock(XWikiLock lock, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            session.delete(lock);

            if (bTransaction)
                endTransaction(context, true);
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_LOCK,
                    "Exception while deleting lock", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public List loadLinks(long docId, XWikiContext context, boolean bTransaction) throws XWikiException {
        List links=new ArrayList();
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            Query query = session.createQuery(" from XWikiLink as link where link.id.docId = :docId");
            query.setLong("docId", docId);

            links = query.list();

            if (bTransaction)
                endTransaction(context, false, false);
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_LINKS,
                    "Exception while loading links", e);
        }
        finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}
        }
        return links;
    }

    public List loadBacklinks(String fullName, XWikiContext context, boolean bTransaction) throws XWikiException {
        List backlinks = new ArrayList();
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(false, context);
            }
            Session session = getSession(context);

            //the select clause is compulsory to reach the fullName i.e. the page pointed
            Query query = session.createQuery("select backlink.fullName from XWikiLink as backlink where backlink.id.link = :backlink");
            query.setString("backlink", fullName);

            backlinks = query.list();

            if (bTransaction)
                endTransaction(context, false, false);
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_BACKLINKS,
                    "Exception while loading backlinks", e);
        }
        finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}
        }
        return backlinks;
    }

    public void saveLinks(XWikiDocument doc, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            // need to delete existing links before saving the page's one
            deleteLinks(doc.getId(), context, bTransaction);

            // necessary to blank links from doc
            context.remove("links");

            // call to RenderEngine and converting the list of links into a list of backlinks
            try {
                XWikiRenderer renderer = context.getWiki().getRenderingEngine().getRenderer("wiki");
                renderer.render(doc.getContent(), doc, doc, context);
            } catch (Exception e) {
                // If the rendering fails lets forget backlinks without errors
            }

            List links = (List)context.get("links");

            if (links != null){
                for (int i=0;i<links.size();i++) {
                    // XWikiLink is the object declared in the Hibernate mapping
                    XWikiLink link = new XWikiLink();
                    link.setDocId(doc.getId());
                    link.setLink((String)links.get(i));
                    link.setFullName(doc.getFullName());
                    session.save(link);
                }
            }

            if (bTransaction) {
                endTransaction(context, true);
            }
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_LINKS,
                    "Exception while saving links", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }

    public void deleteLinks(long docId, XWikiContext context, boolean bTransaction) throws XWikiException {
        try {
            if (bTransaction) {
                checkHibernate(context);
                bTransaction = beginTransaction(context);
            }
            Session session = getSession(context);

            Query query = session.createQuery(" from XWikiLink as link where link.id.docId = :docId");
            query.setLong("docId", docId);

            List links = query.list();
            for (int i=0;i<links.size();i++) {
                XWikiLink link = (XWikiLink) links.get(i);
                session.delete(link);
            }

            if (bTransaction)
                endTransaction(context, true);
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_DELETING_LINKS,
                    "Exception while deleting links", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false);
            } catch (Exception e) {}
        }
    }


    public void getContent(XWikiDocument doc, StringBuffer buf) {
        buf.append(doc.getContent());
    }

    public List getClassList(XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        try {
            checkHibernate(context);
            bTransaction = beginTransaction(false, context);
            Session session = getSession(context);

            Query query = session.createQuery("select doc.fullName from XWikiDocument as doc where (doc.xWikiClassXML is not null and doc.xWikiClassXML<>'')");
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                String name = (String)it.next();
                list.add(name);
            }

            if (useClassesTable(false, context)) {
                query = session.createQuery("select bclass.name from BaseClass as bclass");
                it = query.list().iterator();
                while (it.hasNext()) {
                    String name = (String)it.next();
                    if (!list.contains(name))
                        list.add(name);
                }
            }
            if (bTransaction)
                endTransaction(context, false, false);
            return list;
        }
        catch (Exception e) {
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching class list", e);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}
        }
    }

    private boolean useClassesTable(boolean write, XWikiContext context) {
        String param = "xwiki.store.hibernate.useclasstables";
        if (write)
         return ("1".equals(context.getWiki().Param(param + ".write", "0")));
        else
         return ("1".equals(context.getWiki().Param(param + ".read", "1")));
    }

    public List search(String sql, int nb, int start, Object[][] whereParams, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;

        if (sql==null)
            return null;

        MonitorPlugin monitor  = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
                monitor.startTimer("hibernate");
            checkHibernate(context);
            bTransaction = beginTransaction(false, context);
            Session session = getSession(context);
            if (whereParams != null)
                sql = sql + generateWhereStatement(sql, whereParams);
            Query query = session.createQuery(sql);
            if (whereParams != null)
            {
                for (int i = 0; i < whereParams.length; i++)
                    query.setString(i, (String) whereParams[i][1]);
            }
            if (start!=0)
                query.setFirstResult(start);
            if (nb!=0)
                query.setMaxResults(nb);
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                list.add(it.next());
            }
            return list;
        }
        catch (Exception e) {
            Object[] args = { sql };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching documents with sql {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    private String generateWhereStatement(String sql, Object[][] whereParams) {
        StringBuffer str =  new StringBuffer();

        str.append(" where ");
        for (int i = 0; i < whereParams.length; i++)
        {
            if (i > 0)
            {
                if (whereParams[i - 1].length >= 4 && whereParams[i - 1][3] != "" && whereParams[i - 1][3] != null)
                {
                    str.append(" ");
                    str.append(whereParams[i - 1][3]);
                    str.append(" ");
                }
                else
                    str.append(" and ");
            }
            str.append(whereParams[i][0]);
            if (whereParams[i].length >= 3 && whereParams[i][2] != "" && whereParams[i][2] != null)
            {
                str.append(" ");
                str.append(whereParams[i][2]);
                str.append(" ");
            }
            else
                str.append(" = ");
            str.append(" ?");
        }
        return str.toString();
    }


    public List search(String sql, int nb, int start, XWikiContext context) throws XWikiException {
        return search(sql, nb, start, null, context);
    }

    public List search(Query query, int nb, int start, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;

        if (query==null)
            return null;

        MonitorPlugin monitor  = Util.getMonitorPlugin(context);
        try {
            // Start monitoring timer
            if (monitor!=null)
                monitor.startTimer("hibernate", query.getQueryString());
            checkHibernate(context);
            bTransaction = beginTransaction(false, context);
            Session session = getSession(context);
            if (start!=0)
                query.setFirstResult(start);
            if (nb!=0)
                query.setMaxResults(nb);
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                list.add(it.next());
            }
            if (bTransaction)
                endTransaction(context, false, false);
            return list;
        }
        catch (Exception e) {
            Object[] args = { query.toString() };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching documents with sql {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public List searchDocumentsNames(String wheresql, int nb, int start, String selectColumns, XWikiContext context) throws XWikiException {
        boolean bTransaction = false;
        MonitorPlugin monitor  = Util.getMonitorPlugin(context);
        try {
            if(selectColumns == null) {
                selectColumns = "";
            }
            else {
                selectColumns = selectColumns.trim();
            }
            StringBuffer sql = new StringBuffer("select distinct doc.web, doc.name");
            if (!selectColumns.equals("")) {
                sql.append(",");
                sql.append(selectColumns);
            }

            if (wheresql==null)
                wheresql = "";

            int orderPos = wheresql.toLowerCase().indexOf("order by");
            if (orderPos >= 0)
            {
                orderPos += "order by".length();
                String orderStatement = wheresql.substring(orderPos + 1);
                orderStatement = orderStatement.replaceAll("([d|D][e|E][s|S][c|C])|([a|A][s|S][c|C])", "");
                sql.append(", ").append(orderStatement);
            }

            sql.append(" from XWikiDocument as doc");

            wheresql = wheresql.trim();
            if (!wheresql.equals("")) {
                if ((!wheresql.startsWith("where"))&&(!wheresql.startsWith(",")))
                    sql.append(" where ");
                else
                    sql.append(" ");

                sql.append(wheresql);
            }
            String ssql = sql.toString();

            // Start monitoring timer
            if (monitor!=null)
                monitor.startTimer("hibernate", ssql);

            checkHibernate(context);
            bTransaction = beginTransaction(false, context);
            Session session = getSession(context);
            Query query = session.createQuery(ssql);
            if (start!=0)
                query.setFirstResult(start);
            if (nb!=0)
                query.setMaxResults(nb);
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                Object[] result = (Object[]) it.next();
                String name = (String) result[0] + "." + (String)result[1];
                list.add(name);
            }
            return list;
        }
        catch (Exception e) {
            Object[] args = { wheresql  };
            // Object[] args = { ((wheresql==null) ? "" : wheresql)  };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching documents with sql {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public List searchDocuments(String wheresql, boolean distinctbylanguage, boolean customMapping, boolean checkRight, int nb, int start, XWikiContext context) throws XWikiException {
        boolean bTransaction = true;
        MonitorPlugin monitor  = Util.getMonitorPlugin(context);
        try {
            StringBuffer sql;
            if (distinctbylanguage)
                sql = new StringBuffer("select distinct doc.web, doc.name, doc.language from XWikiDocument as doc");
            else
                sql = new StringBuffer("select distinct doc.web, doc.name from XWikiDocument as doc");

            if (wheresql==null)
                wheresql = "";
            wheresql.trim();
            if (!wheresql.equals("")) {
                if ((!wheresql.startsWith("where"))&&(!wheresql.startsWith(",")))
                    sql.append(" where ");
                else
                    sql.append(" ");

                sql.append(wheresql);
            }
            String ssql = sql.toString();

            // Start monitoring timer
            if (monitor!=null)
                monitor.startTimer("hibernate", ssql);

            checkHibernate(context);
            if (bTransaction) {
                // Inject everything until we know what's needed
                SessionFactory sfactory = customMapping ? injectCustomMappingsInSessionFactory(context) : getSessionFactory();
                bTransaction = beginTransaction(sfactory, false, context);
            }
            Session session = getSession(context);

            Query query = session.createQuery(ssql);
            if (start!=0)
                query.setFirstResult(start);
            if (nb!=0)
                query.setMaxResults(nb);
            Iterator it = query.list().iterator();
            List list = new ArrayList();
            while (it.hasNext()) {
                Object[] result = (Object[]) it.next();

                XWikiDocument doc = new XWikiDocument((String)result[0], (String)result[1]);
                if (checkRight) {
                    if (context.getWiki().getRightService().checkAccess("view", doc, context)==false)
                        continue;
                }

                String name = doc.getFullName();
                if (distinctbylanguage) {
                    String language = (String) result[2];
                    if ((language==null)||(language.equals("")))
                        list.add(context.getWiki().getDocument(name, context));
                    else {
                        XWikiDocument doc2 = context.getWiki().getDocument(name, context);
                        list.add(doc2.getTranslatedDocument(language, context));
                    }
                } else {
                    list.add(context.getWiki().getDocument(name, context));
                }
            }
            if (bTransaction)
                endTransaction(context, false, false);
            return list;
        }
        catch (Exception e) {
            Object[] args = { wheresql  };
            // Object[] args = { ((wheresql==null) ? "" : wheresql)  };
            throw new XWikiException( XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SEARCH,
                    "Exception while searching documents with sql {0}", e, args);
        } finally {
            try {
                if (bTransaction)
                    endTransaction(context, false, false);
            } catch (Exception e) {}

            // End monitoring timer
            if (monitor!=null)
                monitor.endTimer("hibernate");
        }
    }

    public boolean isCustomMappingValid(BaseClass bclass, String custommapping1, XWikiContext context) {
        try {
            Configuration hibconfig = makeMapping(bclass.getName(), custommapping1);
            return isValidCustomMapping(bclass.getName(), hibconfig, bclass);
        } catch (Exception e) {
            return false;
        }
    }

    public SessionFactory injectCustomMappingsInSessionFactory(XWikiDocument doc, XWikiContext context) throws XWikiException {
        // If we haven't turned of dynamic custom mappings we should not inject them
        if (context.getWiki().hasDynamicCustomMappings()==false)
           return getSessionFactory();

        boolean result = injectCustomMappings(doc, context);
        if (result==false)
            return getSessionFactory();

        Configuration config = getConfiguration();
        SessionFactoryImpl sfactory = (SessionFactoryImpl) config.buildSessionFactory();
        Settings settings = sfactory.getSettings();
        ConnectionProvider provider = ((SessionFactoryImpl)getSessionFactory()).getSettings().getConnectionProvider();
        Field field = null;
        try {
            field = settings.getClass().getDeclaredField("connectionProvider");
            field.setAccessible(true);
            field.set(settings, provider);
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_MAPPING_INJECTION_FAILED, "Mapping injection failed", e);
        }
        return sfactory;
    }

    public void injectCustomMappings(XWikiContext context) throws XWikiException {
        SessionFactory sfactory = injectCustomMappingsInSessionFactory(context);
        setSessionFactory(sfactory);
    }

    public void injectUpdatedCustomMappings(XWikiContext context) throws XWikiException {
        Configuration config = getConfiguration();
        setSessionFactory(injectInSessionFactory(config));
    }

    public SessionFactory injectCustomMappingsInSessionFactory(BaseClass bclass, XWikiContext context) throws XWikiException {
        boolean result = injectCustomMapping(bclass, context);
        if (result==false)
            return getSessionFactory();

        Configuration config = getConfiguration();
        return injectInSessionFactory(config);
    }

    public SessionFactory injectInSessionFactory(Configuration config) throws XWikiException {
        SessionFactoryImpl sfactory = (SessionFactoryImpl) config.buildSessionFactory();
        Settings settings = sfactory.getSettings();
        ConnectionProvider provider = ((SessionFactoryImpl)getSessionFactory()).getSettings().getConnectionProvider();
        Field field = null;
        try {
            field = settings.getClass().getDeclaredField("connectionProvider");
            field.setAccessible(true);
            field.set(settings, provider);
        } catch (Exception e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_MAPPING_INJECTION_FAILED, "Mapping injection failed", e);
        }
        return sfactory;
    }

    public SessionFactory injectCustomMappingsInSessionFactory(XWikiContext context) throws XWikiException {
        // If we haven't turned of dynamic custom mappings we should not inject them
        if (context.getWiki().hasDynamicCustomMappings()==false)
           return getSessionFactory();

        List list;
        if (useClassesTable(true, context))
          list = searchDocuments(", BaseClass as bclass where bclass.name=doc.fullName and bclass.customMapping is not null",
                                    true, false, false, 0, 0, context);
          list = searchDocuments("",
                                    true, false, false, 0, 0, context);
        boolean result = false;

        for (int i=0;i<list.size();i++) {
            XWikiDocument doc = (XWikiDocument)list.get(i);
            if (doc.getxWikiClass().getFieldList().size()>0 )
             result |= injectCustomMapping(doc.getxWikiClass(), context);
        }

        if (result==false)
            return getSessionFactory();

        Configuration config = getConfiguration();
        return injectInSessionFactory(config);
    }

    public boolean injectCustomMappings(XWikiDocument doc, XWikiContext context) throws XWikiException {
        // If we haven't turned of dynamic custom mappings we should not inject them
        if (context.getWiki().hasDynamicCustomMappings()==false)
           return false;

        boolean result = false;
        Iterator it = doc.getxWikiObjects().values().iterator();
        while (it.hasNext()) {
            Vector objects = (Vector) it.next();
            for (int i=0;i<objects.size();i++) {
                BaseObject obj = (BaseObject)objects.get(i);
                if (obj!=null) {
                    result |=  injectCustomMapping(obj.getxWikiClass(context), context);
                }
            }
        }
        return result;
    }

    public boolean injectCustomMapping(BaseClass doc1class, XWikiContext context) throws XWikiException {
        // If we haven't turned of dynamic custom mappings we should not inject them
        if (context.getWiki().hasDynamicCustomMappings()==false)
           return false;

        String custommapping = doc1class.getCustomMapping();
        if (!doc1class.hasExternalCustomMapping())
           return false;

        Configuration config = getConfiguration();

        // don't add a mapping that's already there
        if (config.getClassMapping(doc1class.getName())!=null)
            return true;

        Configuration mapconfig = makeMapping(doc1class.getName(), custommapping);
        if (!isValidCustomMapping(doc1class.getName(), mapconfig, doc1class))
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_HIBERNATE_INVALID_MAPPING, "Invalid Custom Mapping");

        config.addXML(makeMapping(doc1class.getName() , "xwikicustom_" + doc1class.getName().replace('.','_'), custommapping));
        config.buildMappings();
        return true;
    }

    private boolean isValidCustomMapping(String className, Configuration hibconfig, BaseClass bclass) {
        PersistentClass mapping = hibconfig.getClassMapping(className);
        if (mapping==null)
            return true;

        Iterator it = mapping.getPropertyIterator();
        while (it.hasNext()) {
            Property hibprop = (Property) it.next();
            String propname = hibprop.getName();
            PropertyClass propclass = (PropertyClass) bclass.getField(propname);
            if (propclass==null) {
                log.warn("Mapping contains invalid field name " + propname);
                return false;
            }

            boolean result = isValidColumnType(hibprop.getValue().getType().getName(), propclass.getClassName());
            if (result==false) {
                log.warn("Mapping contains invalid type in field " + propname);
                return false;
            }
        }

        return true;
    }

    public List getCustomMappingPropertyList(BaseClass bclass) {
        List list = new ArrayList();
        Configuration hibconfig;
        if (bclass.hasExternalCustomMapping())
          hibconfig = makeMapping(bclass.getName(), bclass.getCustomMapping());
        else
          hibconfig = getConfiguration();
        PersistentClass mapping = hibconfig.getClassMapping(bclass.getName());
        if (mapping==null)
            return null;

        Iterator it = mapping.getPropertyIterator();
        while (it.hasNext()) {
            Property hibprop = (Property) it.next();
            String propname = hibprop.getName();
            list.add(propname);
        }
        return list;
    }


    private boolean isValidColumnType(String name, String className) {
        String[] validtypes = (String[]) validTypesMap.get(className);
        if (validtypes==null)
            return true;
        else
            return ArrayUtils.contains(validtypes, name);
    }


    public XWikiBatcherStats getBatcherStats() {
        return null; // XWikiBatcher.getSQLStats();
    }

    public void resetBatcherStats() {
        // XWikiBatcher.getSQLStats().resetStats();
    }


    public List searchDocumentsNames(String wheresql, XWikiContext context) throws XWikiException {
        return searchDocumentsNames(wheresql, 0, 0, "", context);
    }

    public List searchDocumentsNames(String wheresql, int nb, int start, XWikiContext context) throws XWikiException {
        return searchDocumentsNames(wheresql, nb, start, "", context);
    }

    public List searchDocuments(String wheresql, XWikiContext context) throws XWikiException {
        return searchDocuments(wheresql, true, 0, 0, context);
    }

    public List searchDocuments(String wheresql, boolean distinctbylanguage, XWikiContext context) throws XWikiException {
        return searchDocuments(wheresql, distinctbylanguage, 0, 0, context);
    }

    public List searchDocuments(String wheresql, boolean distinctbylanguage, boolean customMapping, XWikiContext context) throws XWikiException {
        return searchDocuments(wheresql, distinctbylanguage, customMapping, 0, 0, context);
    }

    public List searchDocuments(String wheresql, int nb, int start, XWikiContext context) throws XWikiException {
        return searchDocuments(wheresql, true, nb, start, context);
    }

    public List searchDocuments(String wheresql, boolean distinctbyname, int nb, int start, XWikiContext context) throws XWikiException {
          return searchDocuments(wheresql, distinctbyname, false, nb, start, context);
    }

    public List searchDocuments(String wheresql, boolean distinctbyname, boolean customMapping, int nb, int start, XWikiContext context) throws XWikiException {
        return searchDocuments(wheresql,  distinctbyname, customMapping, true, nb, start, context);
    }

	public List getTranslationList(XWikiDocument doc, XWikiContext context) throws XWikiException {
		List result = new ArrayList();
        String hql = "select doc.language from XWikiDocument as doc where doc.web = '"
                + Utils.SQLFilter(doc.getWeb()) + "' and doc.name = '" + Utils.SQLFilter(doc.getName()) + "' and doc.language <> ''";

        List list = context.getWiki().search(hql, context);
        if ((list == null) || (list.size() == 0)) {
            return result;
        }

        for (int i = 0; i < list.size(); i++) {
            result.add(list.get(i));
        }
        return result;
	}

  /*
    public void updateArchive(String text) throws XWikiException {
        try {
            Lines lines = new Lines(text);
            if (archive != null)
                archive.addRevision(lines.toArray(), "");
            else
                archive = new Archive(lines.toArray(), getFullName(), getVersion());
        }
        catch (Exception e) {
            Object[] args = {getFullName()};
            throw new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_STORE_ARCHIVEFORMAT,
                    "Exception while manipulating the archive for doc {0}", e, args);
        }
    }
  */
}

