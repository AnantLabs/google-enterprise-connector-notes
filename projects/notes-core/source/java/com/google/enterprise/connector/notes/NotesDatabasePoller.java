// Copyright 2011 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.notes;

import com.google.common.annotations.VisibleForTesting;
import com.google.enterprise.connector.notes.client.NotesACL;
import com.google.enterprise.connector.notes.client.NotesACLEntry;
import com.google.enterprise.connector.notes.client.NotesDatabase;
import com.google.enterprise.connector.notes.client.NotesDateTime;
import com.google.enterprise.connector.notes.client.NotesDocument;
import com.google.enterprise.connector.notes.client.NotesDocumentCollection;
import com.google.enterprise.connector.notes.client.NotesItem;
import com.google.enterprise.connector.notes.client.NotesSession;
import com.google.enterprise.connector.notes.client.NotesView;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SpiConstants.ActionType;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

class NotesDatabasePoller {
  private static final String CLASS_NAME = NotesDatabasePoller.class.getName();
  private static final Logger LOGGER = Logger.getLogger(CLASS_NAME);

  private final NotesConnectorSession notesConnectorSession;
  private final Map<String, Date> lastCrawlCache;

  public static void resetDatabases(NotesConnectorSession ncs) {
    final String METHOD = "resetDatabases";
    NotesSession ns = null;
    LOGGER.entering(CLASS_NAME, METHOD);
    try {
      ns = ncs.createNotesSession();
      NotesDatabase cdb = ns.getDatabase(
          ncs.getServer(), ncs.getDatabase());

      // Reset the last update date for each configured database
      NotesView srcdbView = cdb.getView(NCCONST.VIEWDATABASES);
      srcdbView.refresh();
      NotesDocument srcdbDoc = srcdbView.getFirstDocument();
      while (null != srcdbDoc) {
        LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
            "Connector reset - Resetting database last update date for "
            + srcdbDoc.getItemValue(NCCONST.DITM_DBNAME));
        srcdbDoc.removeItem(NCCONST.DITM_LASTUPDATE);
        srcdbDoc.removeItem(NCCONST.DITM_ACLTEXT);
        srcdbDoc.save(true);
        NotesDocument prevDoc = srcdbDoc;
        srcdbDoc = srcdbView.getNextDocument(prevDoc);
        prevDoc.recycle();
      }
      srcdbView.recycle();

      // Reset last cache update date time for directory update
      if (ncs.getUserGroupManager().resetLastCacheUpdate()) {
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Last cache update date time is reset");
      }
    } catch (Exception e) {
      LOGGER.logp(Level.SEVERE, CLASS_NAME, METHOD,
          "Error resetting connector.", e);
    } finally {
      ncs.closeNotesSession(ns);
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
  }

  public NotesDatabasePoller(NotesConnectorSession notesConnectorSession,
      Map<String, Date> lastCrawlCache) {
    this.notesConnectorSession = notesConnectorSession;
    this.lastCrawlCache = lastCrawlCache;
  }

  public void pollDatabases(NotesSession ns, NotesDatabase cdb,
      int maxDepth) {
    final String METHOD = "pollDatabases";
    LOGGER.entering(CLASS_NAME, METHOD);
    try {
      // TODO: use Date or Calendar to avoid the Notes library
      // dependency on the operating system's settings for date
      // formats.
      NotesDateTime pollTime = ns.createDateTime("1/1/1900");
      pollTime.setNow();

      NotesView templateView = cdb.getView(NCCONST.VIEWTEMPLATES);
      NotesView srcdbView = cdb.getView(NCCONST.VIEWDATABASES);
      srcdbView.refresh();
      NotesView vwSubmitQ = cdb.getView(NCCONST.VIEWSUBMITQ);
      NotesView vwCrawlQ = cdb.getView(NCCONST.VIEWCRAWLQ);

      // TODO: Make this loop shutdown aware

      Map<String, Date> nextBatch = new HashMap<String, Date>();
      NotesDocument srcdbDoc = srcdbView.getFirstDocument();
      while (null != srcdbDoc) {
        vwSubmitQ.refresh();
        vwCrawlQ.refresh();
        int qDepth = vwSubmitQ.getEntryCount() + vwCrawlQ.getEntryCount();
        LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
            "Total documents in crawl and submit queues is: " + qDepth);
        if (vwSubmitQ.getEntryCount() + vwCrawlQ.getEntryCount() > maxDepth) {
          LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
              "Queue threshold reached.  Suspending polling. size/max="
              + qDepth + "/" + maxDepth);
          srcdbDoc.recycle();
          break;
        }
        LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
            "Source Database Config Document " +
            srcdbDoc.getItemValue(NCCONST.DITM_DBNAME));
        pollSourceDatabase(ns, cdb, srcdbDoc, templateView, pollTime,
            nextBatch);
        NotesDocument prevDoc = srcdbDoc;
        srcdbDoc = srcdbView.getNextDocument(prevDoc);
        prevDoc.recycle();
      }
      // TODO(tdnguyen): Move the cache update and the setting of
      // DITM_LASTUPDATE field to NotesConnectorDocumentList.checkpoint method.
      synchronized (lastCrawlCache) {
        lastCrawlCache.clear();
        lastCrawlCache.putAll(nextBatch);
      }

      vwSubmitQ.recycle();
      vwCrawlQ.recycle();
      pollTime.recycle();
      templateView.recycle();
      srcdbView.recycle();
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, CLASS_NAME, e);
    } finally {
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
  }

  @VisibleForTesting
  boolean processACL(NotesSession notesSession,
      NotesDatabase connectorDatabase, NotesDatabase srcdb,
      NotesDocument dbdoc) {
    final String METHOD = "processACL";
    LOGGER.entering(CLASS_NAME, METHOD);
    NotesACL acl = null;
    try {
      // To determine if the ACL has changed we check the log
      String aclActivityText = srcdb.getACLActivityLog()
          .firstElement().toString();
      if (aclActivityText.contentEquals(
              dbdoc.getItemValueString(NCCONST.DITM_ACLTEXT))) {
        LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
            "ACL has not changed.  Skipping ACL processing. ");
        return false;
      }
      LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
          "New ACL Text is. " + aclActivityText);

      // Build the lists of allowed/denied users and groups.
      acl = srcdb.getACL();
      ArrayList<String> permitUsers = new ArrayList<String>();
      ArrayList<String> permitGroups = new ArrayList<String>();
      ArrayList<String> noAccessUsers = new ArrayList<String>();
      ArrayList<String> noAccessGroups = new ArrayList<String>();
      getPermitDeny(acl, permitUsers, permitGroups, noAccessUsers,
        noAccessGroups, notesSession);

      // If the database is configured to use ACLs for
      // authorization, check to see if we should send
      // inherited ACLs (GSA 7.0+).
      if (dbdoc.getItemValueString(NCCONST.DITM_AUTHTYPE)
          .contentEquals(NCCONST.AUTH_ACL)) {
        if (notesConnectorSession.getTraversalManager()
            .supportsInheritedAcls()) {
          if (LOGGER.isLoggable(Level.FINER)) {
            LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
                "Creating ACL records for database "
                + dbdoc.getItemValueString(NCCONST.DITM_DBNAME));
          }
          // We want two database ACLs, one for use when
          // documents in the database have readers, one when
          // they don't. Inserting a second database ACL
          // document later will require a restructuring of the
          // way NotesConnectorDocumentList works, so for now,
          // simply create two database ACL crawl docs.
          Collection<String> gsaPermitUsers =
              notesConnectorSession.getUserGroupManager()
              .mapNotesNamesToGsaNames(notesSession, permitUsers, false);
          Collection<String> gsaNoAccessUsers =
              notesConnectorSession.getUserGroupManager()
              .mapNotesNamesToGsaNames(notesSession, noAccessUsers, false);
          Collection<String> gsaPermitGroups =
              GsaUtil.getGsaGroups(permitGroups,
                  notesConnectorSession.getGsaGroupPrefix());
          Collection<String> gsaNoAccessGroups =
              GsaUtil.getGsaGroups(noAccessGroups,
                  notesConnectorSession.getGsaGroupPrefix());
          createDatabaseAclDocuments(connectorDatabase, dbdoc, gsaPermitUsers,
              gsaNoAccessUsers, gsaPermitGroups, gsaNoAccessGroups);
        } else {
          logGsaPolicyAcl(dbdoc);
        }
      }

      // Update the dbdoc.
      dbdoc.replaceItemValue(NCCONST.DITM_ACLTEXT, aclActivityText);
      updateTextList(dbdoc, NCCONST.NCITM_DBNOACCESSUSERS, noAccessUsers);
      updateTextList(dbdoc, NCCONST.NCITM_DBPERMITUSERS, permitUsers);
      updateTextList(dbdoc, NCCONST.NCITM_DBPERMITGROUPS, permitGroups);
      updateTextList(dbdoc, NCCONST.NCITM_DBNOACCESSGROUPS, noAccessGroups);
    } catch (Exception e) {
      // TODO: should we return false here?
      LOGGER.log(Level.SEVERE, CLASS_NAME, e);
    } finally {
      if (null != acl) {
        try {
          acl.recycle();
        } catch (RepositoryException e) {
        }
      }
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
    return true;
  }

  private boolean createDatabaseAclDocuments(
      NotesDatabase connectorDatabase, NotesDocument dbdoc,
      Collection<String> gsaPermitUsers, Collection<String> gsaNoAccessUsers,
      Collection<String> gsaPermitGroups,
      Collection<String> gsaNoAccessGroups) {
    final String METHOD = "createDatabaseAclDocuments";

    try {
      createDatabaseAclDocument(connectorDatabase, dbdoc,
          NCCONST.DB_ACL_INHERIT_TYPE_ANDBOTH, gsaPermitUsers,
          gsaNoAccessUsers, gsaPermitGroups, gsaNoAccessGroups);
      createDatabaseAclDocument(connectorDatabase, dbdoc,
          NCCONST.DB_ACL_INHERIT_TYPE_PARENTOVERRIDES, gsaPermitUsers,
          gsaNoAccessUsers, gsaPermitGroups, gsaNoAccessGroups);
      return true;
    } catch (Throwable t) {
      LOGGER.logp(Level.WARNING, CLASS_NAME, METHOD,
          "Failed to cache updated ACL for database", t);
      return false;
    }
  }

  /**
   * Creates a crawl doc representing an ACL for the current database.
   */
  private void createDatabaseAclDocument(NotesDatabase connectorDatabase,
      NotesDocument dbdoc, String inheritType,
      Collection<String> gsaPermitUsers, Collection<String> gsaNoAccessUsers,
      Collection<String> gsaPermitGroups,
      Collection<String> gsaNoAccessGroups) throws Exception {
    final String METHOD = "createDatabaseAclDocument";
    NotesDocument aclDoc = connectorDatabase.createDocument();
    try {
      String server = dbdoc.getItemValueString(NCCONST.DITM_SERVER);
      String domain = notesConnectorSession.getDomain(server);
      String replicaId = dbdoc.getItemValueString(NCCONST.DITM_REPLICAID);
      NotesDocId replicaUrl = new NotesDocId();
      replicaUrl.setHost(server + domain);
      replicaUrl.setReplicaId(replicaId);
      String id = replicaUrl.toString() + "/" + inheritType;

      // This is a connector-internal flag that lets us
      // distinguish these crawl docs later.
      aclDoc.appendItemValue(NCCONST.NCITM_DBACL, "true");
      aclDoc.appendItemValue(NCCONST.NCITM_DBACLINHERITTYPE, inheritType);

      // Create a crawl doc for the database ACL. Use
      // STATEFETCHED to have this document processed by
      // TraversalManager. I'm setting UNID and docid
      // because they're used later.
      aclDoc.appendItemValue(NCCONST.NCITM_STATE, NCCONST.STATEFETCHED);
      aclDoc.appendItemValue(NCCONST.ITM_ACTION,
          ActionType.ADD.toString());
      aclDoc.appendItemValue(NCCONST.ITMFORM, NCCONST.FORMCRAWLREQUEST);
      aclDoc.appendItemValue(NCCONST.NCITM_UNID, replicaId);
      aclDoc.appendItemValue(NCCONST.ITM_DOCID, id);
      aclDoc.appendItemValue(NCCONST.NCITM_REPLICAID, replicaId);
      aclDoc.appendItemValue(NCCONST.NCITM_SERVER, server);
      aclDoc.appendItemValue(NCCONST.NCITM_DOMAIN, domain);
      updateTextList(aclDoc, NCCONST.NCITM_DBPERMITUSERS, gsaPermitUsers);
      updateTextList(aclDoc, NCCONST.NCITM_DBNOACCESSUSERS, gsaNoAccessUsers);
      updateTextList(aclDoc, NCCONST.NCITM_DBPERMITGROUPS, gsaPermitGroups);
      updateTextList(aclDoc, NCCONST.NCITM_DBNOACCESSGROUPS,
          gsaNoAccessGroups);
      if (LOGGER.isLoggable(Level.FINE)) {
        String message = "Database acl: " + id
            + "\nallow users: " + gsaPermitUsers
            + "\nallow groups: " + gsaPermitGroups
            + "\ndeny users: " + gsaNoAccessUsers
            + "\ndeny groups: " + gsaNoAccessGroups;
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD, message);
      }
      aclDoc.save();
    } finally {
      if (aclDoc != null) {
        aclDoc.recycle();
      }
    }
  }

  private void updateTextList(NotesDocument dbdoc, String itemName,
      Collection<String> textData) throws RepositoryException {
    NotesItem item = dbdoc.replaceItemValue(itemName, null);
    try {
      item.setSummary(false);
      for (String text : textData) {
        item.appendToTextList(text);
      }
    } finally {
      item.recycle();
    }
  }

  @VisibleForTesting
  void getPermitDeny(NotesACL acl, List<String> permitUsers,
      List<String> permitGroups, List<String> noAccessUsers,
      List<String> noAccessGroups, NotesSession ns) throws RepositoryException {
    final String METHOD = "getPermitDeny";
    NotesACLEntry ae = acl.getFirstEntry();
    while (ae != null) {
      LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
          "Checking ACL Entry: " + ae.getName());
      int userType = ae.getUserType();
      // If this is a user explicitly listed with NO ACCESS
      if (NotesACL.LEVEL_DEPOSITOR > ae.getLevel()) {
        // Send both specified and unspecified users with NO ACCESS to GSA as
        // DENY users.  As a result, unspecified groups with NO ACCESS will also
        // be included in the DENY user list but they will not have any impact
        // to authenticated users.
        if ((userType == NotesACLEntry.TYPE_PERSON) ||
            (userType == NotesACLEntry.TYPE_UNSPECIFIED)) {
          LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
              "Adding the user entry to deny list: " + ae.getName());
          noAccessUsers.add(ae.getName().toLowerCase());
        }
        // Skip unspecified groups such as -Default- and Anonymous.
        // Do not need to send deny access for groups and unspecified groups.
      }

      // If this entry has an access level greater than DEPOSITOR
      if (NotesACL.LEVEL_DEPOSITOR < ae.getLevel()) {
        // Add to the PERMIT USERS if they are a user
        if ((userType == NotesACLEntry.TYPE_PERSON) ||
            (userType == NotesACLEntry.TYPE_UNSPECIFIED)) {
          LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
              "Adding the user entry to person allow list: " + ae.getName());
          permitUsers.add(ae.getName().toLowerCase());
        }
        // Add to the PERMIT GROUPS if they are a group
        if  ((userType == NotesACLEntry.TYPE_MIXED_GROUP) ||
            (userType == NotesACLEntry.TYPE_PERSON_GROUP) ||
            (userType == NotesACLEntry.TYPE_UNSPECIFIED)) {
          LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
              "Adding the user entry to group allow list: " + ae.getName());
          permitGroups.add(ae.getName().toLowerCase());
        }
      }
      NotesACLEntry prevae = ae;
      ae = acl.getNextEntry(prevae);
      prevae.recycle();
    }
  }

  /**
   * Logs a warning about the possibility of an outdated policy ACL.
   */
  @VisibleForTesting
  void logGsaPolicyAcl(NotesDocument dbdoc) throws RepositoryException {
    // Get the database URL pattern.
    String server = dbdoc.getItemValueString(NCCONST.DITM_SERVER);
    String domain = notesConnectorSession.getDomain(server);
    String replicaId = dbdoc.getItemValueString(NCCONST.DITM_REPLICAID);
    NotesDocId id = new NotesDocId();
    id.setHost(server + domain);
    id.setReplicaId(replicaId);
    String urlPattern = MessageFormat.format(
        notesConnectorSession.getConnector().getPolicyAclPattern(),
        notesConnectorSession.getConnector().getGoogleConnectorName(),
        id.toString());

    // TODO(jlacey): Delete this warning in a future release.
    LOGGER.log(Level.WARNING, "The database ACL for {0} has changed. "
        + "You should delete or update the policy ACL, if one exists for {1}.",
        new Object[] { replicaId, urlPattern });
  }

  /*
   * This function should probably return the number of documents queued
   * that way we can prevent overflowing the database
   */
  private void pollSourceDatabase(NotesSession ns, NotesDatabase cdb,
      NotesDocument srcdbDoc, NotesView templateView, NotesDateTime pollTime,
      Map<String, Date> nextBatch) {
    final String METHOD = "pollSourceDatabase";
    NotesDateTime lastUpdated = null;
    NotesDateTime searchLastUpdated = null;
    Vector<?> lastUpdatedV = null;
    LOGGER.entering(CLASS_NAME, METHOD);

    try {
      // There are configuration options to stop and disable databases
      // In either of these states, we skip processing the database
      if (1 != srcdbDoc.getItemValueInteger(NCCONST.DITM_CRAWLENABLED)) {
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Skipping database - Database is DISABLED.");
        return;
      }
      if (1 == srcdbDoc.getItemValueInteger(NCCONST.DITM_STOPPED)) {
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Skipping database - Database is STOPPED.");
        return;
      }

      // When was this database last updated?
      lastUpdatedV = srcdbDoc.getItemValue(NCCONST.DITM_LASTUPDATE);
      if (0 < lastUpdatedV.size()) {
        lastUpdated = (NotesDateTime) lastUpdatedV.firstElement();
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Last processed time was " + lastUpdated);
        searchLastUpdated = ns.createDateTime(lastUpdated.toJavaDate());
        if (Util.isNotesVersionEightOrOlder(ns.getNotesVersion())) {
          // Adjust -1 second to include documents whose last modified time is
          // equal to the last updated time.
          searchLastUpdated.adjustSecond(-1);
          LOGGER.log(Level.FINEST,
              "Last processed time was adjusted by -1 second [{0}]",
              searchLastUpdated);
        }
      } else {
        lastUpdated = ns.createDateTime("1/1/1980");
        searchLastUpdated = ns.createDateTime(lastUpdated.toJavaDate());
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Database has never been processed.");
      }

      // What's our poll interval?
      double pollInterval = srcdbDoc.getItemValueInteger(
          NCCONST.DITM_UPDATEFREQUENCY);
      double elapsedMinutes = pollTime.timeDifference(lastUpdated) / 60;
      LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
          "Time difference is : " + elapsedMinutes);

      // Check poll interval
      if (pollInterval > elapsedMinutes) {
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Skipping database - Poll interval has not yet elapsed.");
        lastUpdated.recycle();
        searchLastUpdated.recycle();
        ns.recycle(lastUpdatedV);
        return;
      }

      // Get modified documents
      NotesDatabase srcdb = ns.getDatabase(null, null);
      srcdb.openByReplicaID(
          srcdbDoc.getItemValueString(NCCONST.DITM_SERVER),
          srcdbDoc.getItemValueString(NCCONST.DITM_REPLICAID));

      // Did the database open succeed? If not exit
      if (!srcdb.isOpen()) {
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Skipping database - Database could not be opened.");
        lastUpdated.recycle();
        searchLastUpdated.recycle();
        ns.recycle(lastUpdatedV);
        srcdb.recycle();
        return;
      }

      String dbName = srcdbDoc.getItemValueString(NCCONST.DITM_DBNAME);
      String authType = srcdbDoc.getItemValueString(NCCONST.DITM_AUTHTYPE);
      LOGGER.log(Level.FINE,
          "{0} database is configured using {1} authentication type",
          new Object[] {dbName, authType});
      if (processACL(ns, cdb, srcdb, srcdbDoc)) {
        // Scan database ACLs and update H2 cache
        LOGGER.log(Level.FINE, "Scan ACLs and update H2 for {0} replica",
            srcdb.getReplicaID());
        notesConnectorSession.getUserGroupManager().updateRoles(srcdb);

        // If the ACL has changed and we are using per Document
        // ACLs we need to resend all documents.
        if (authType.contentEquals(NCCONST.AUTH_ACL)) {
          LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
              "Database ACL has changed - Resetting last update "
              + "to reindex all document ACLs.");
          lastUpdated = ns.createDateTime("1/1/1980");
        }
      }

      // From the template, we get the search string to determine
      // which documents should be processed
      NotesDocument templateDoc = templateView.getDocumentByKey(
          srcdbDoc.getItemValueString(NCCONST.DITM_TEMPLATE), true);
      String searchString = templateDoc.getItemValueString(
          NCCONST.TITM_SEARCHSTRING);
      LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
          "Search string is: " + searchString);

      NotesDocumentCollection dc =
          srcdb.search(searchString, searchLastUpdated, 0);
      LOGGER.log(Level.FINE, 
           "{0} Number of documents to be processed: {1}, cache size: {2}",
           new Object[] {srcdb.getFilePath(), dc.getCount(),
               lastCrawlCache.size()});

      NotesDocument curDoc = dc.getFirstDocument();
      while (null != curDoc) {
        String NotesURL = curDoc.getNotesURL();
        NotesDateTime lastModified = curDoc.getLastModified();
        LOGGER.log(Level.FINER, "Processing document {0} last modified on {1}",
            new Object[] {NotesURL, lastModified});
        nextBatch.put(NotesURL, lastModified.toJavaDate());
        Date prevLastModified = lastCrawlCache.get(NotesURL);
        if (prevLastModified != null
                && prevLastModified.equals(lastModified.toJavaDate())) {
          LOGGER.log(Level.FINEST,
              "Skipping previously crawled document: {0}", NotesURL);
          curDoc = nextDocument(dc, curDoc);
          continue;
        }
        if (curDoc.hasItem(NCCONST.NCITM_CONFLICT)) {
          LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
              "Skipping conflict document " + NotesURL);
          curDoc = nextDocument(dc, curDoc);
          continue;
        }

        // Create a new crawl request
        NotesDocument crawlRequestDoc = cdb.createDocument();
        crawlRequestDoc.appendItemValue(NCCONST.NCITM_STATE, NCCONST.STATENEW);
        crawlRequestDoc.appendItemValue(NCCONST.ITM_MIMETYPE,
            NCCONST.DEFAULT_DOCMIMETYPE);

        // Create the fields necessary to crawl the document
        crawlRequestDoc.appendItemValue(NCCONST.ITMFORM,
            NCCONST.FORMCRAWLREQUEST);
        crawlRequestDoc.appendItemValue(NCCONST.NCITM_UNID,
            curDoc.getUniversalID());
        crawlRequestDoc.appendItemValue(NCCONST.NCITM_REPLICAID,
            srcdbDoc.getItemValueString(NCCONST.DITM_REPLICAID));
        crawlRequestDoc.appendItemValue(NCCONST.NCITM_SERVER,
            srcdbDoc.getItemValueString(NCCONST.DITM_SERVER));
        crawlRequestDoc.appendItemValue(NCCONST.NCITM_TEMPLATE,
            srcdbDoc.getItemValueString(NCCONST.DITM_TEMPLATE));
        crawlRequestDoc.appendItemValue(NCCONST.NCITM_DOMAIN,
            srcdbDoc.getItemValueString(NCCONST.DITM_DOMAIN));
        crawlRequestDoc.appendItemValue(NCCONST.NCITM_AUTHTYPE,
            srcdbDoc.getItemValueString(NCCONST.DITM_AUTHTYPE));

        // Map the lock field directly across
        crawlRequestDoc.appendItemValue(NCCONST.ITM_LOCK,
            srcdbDoc.getItemValueString(NCCONST.DITM_LOCKATTRIBUTE)
            .toLowerCase());

        // Add any database level meta data to the document
        crawlRequestDoc.appendItemValue(NCCONST.ITM_GMETAREPLICASERVERS,
            srcdbDoc.getItemValue(NCCONST.DITM_REPLICASERVERS));
        crawlRequestDoc.appendItemValue(NCCONST.ITM_GMETACATEGORIES,
            srcdbDoc.getItemValue(NCCONST.DITM_DBCATEGORIES));
        crawlRequestDoc.appendItemValue(NCCONST.ITM_GMETADATABASE,
            srcdbDoc.getItemValueString(NCCONST.DITM_DBNAME));
        crawlRequestDoc.appendItemValue(NCCONST.ITM_GMETANOTESLINK, NotesURL);

        crawlRequestDoc.save();
        crawlRequestDoc.recycle();  //TEST THIS
        crawlRequestDoc = null;
        if (lastModified.timeDifference(lastUpdated) > 0) {
          lastUpdated = lastModified;
          LOGGER.log(Level.FINEST, "New last updated time: {0}", lastUpdated);
        }
        curDoc = nextDocument(dc, curDoc);
      }
      dc.recycle();

      // Set last modified date
      LOGGER.log(Level.FINE,
          "[{0}] Source database last updated: {1}", new Object[] {
              srcdbDoc.getItemValueString(NCCONST.DITM_DBNAME), lastUpdated});
      srcdbDoc.replaceItemValue(NCCONST.DITM_LASTUPDATE, lastUpdated);
      srcdbDoc.save();

      // TODO: Handle db.search for case where there are more
      // that 5000 documents
      // Suspect this limitation is for DIIOP only and not for RPC access
      // Have sucessfully tested up to 9000 documents

      // Recycle our objects
      srcdb.recycle();
      templateDoc.recycle();
      lastUpdated.recycle();
      searchLastUpdated.recycle();
      ns.recycle(lastUpdatedV);
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, CLASS_NAME, e);
    } finally {
      LOGGER.exiting(CLASS_NAME, METHOD);
    }
  }

  private NotesDocument nextDocument(NotesDocumentCollection dc,
      NotesDocument curDoc) throws RepositoryException {
    NotesDocument nextDoc = dc.getNextDocument(curDoc);
    curDoc.recycle();
    return nextDoc;
  }
}
