package com.google.enterprise.connector.notes;

import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import lotus.domino.*;



public class NotesDatabasePoller {
	private static final String CLASS_NAME = NotesDatabasePoller.class.getName();
	private static final Logger _logger = Logger.getLogger(CLASS_NAME);
	
	lotus.domino.DateTime pollTime = null;			// Configuration database
	lotus.domino.View templateView = null;
	lotus.domino.View unidView = null;
	lotus.domino.View srcdbView = null;

	

		
	// This method will reset any documents in the crawl queue that are
	// in the INCRAWL state back to NEW state
	public static void resetCrawlQueue(NotesConnectorSession ncs){
		final String METHOD = "resetCrawlQueue";
		lotus.domino.Session ns = null;
		
		_logger.entering(CLASS_NAME, METHOD);
		try {
			ns = ncs.createNotesSession();
			lotus.domino.Database cdb = ns.getDatabase(ncs.getServer(), ncs.getDatabase());
			
			// Reset the last update date for each configured database
			View incrawlView = cdb.getView(NCCONST.VIEWINCRAWL);
			incrawlView.refresh();
			lotus.domino.Document srcDoc = incrawlView.getFirstDocument();
			while (null != srcDoc)
			{
				_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Connector starting - Resetting crawl document found in INCRAWL state " + srcDoc.getUniversalID());
				srcDoc.replaceItemValue(NCCONST.NCITM_STATE, NCCONST.STATENEW);
				lotus.domino.Document prevDoc = srcDoc;
				srcDoc = incrawlView.getNextDocument(prevDoc);  
				prevDoc.save();  // Don't save this until we have the next doc in the view.  Otherwise an exception will result
				prevDoc.recycle();
			}
			incrawlView.recycle();
		}
		catch(Exception e)
		{
			_logger.logp(Level.SEVERE, CLASS_NAME, METHOD, "Error resetting crawl document.");
			_logger.log(Level.SEVERE, CLASS_NAME, e); 
		}
		finally
		{
			ncs.closeNotesSession(ns);
			_logger.exiting(CLASS_NAME, METHOD);
		}
	}

	public static void resetDatabases(NotesConnectorSession ncs){
		final String METHOD = "resetDatabases";
		lotus.domino.Session ns = null;
		_logger.entering(CLASS_NAME, METHOD);
		try {
			ns = ncs.createNotesSession();
			lotus.domino.Database cdb = ns.getDatabase(ncs.getServer(), ncs.getDatabase());
			
			// Reset the last update date for each configured database
			View srcdbView = cdb.getView(NCCONST.VIEWDATABASES);
			srcdbView.refresh();
			lotus.domino.Document srcdbDoc = srcdbView.getFirstDocument();
			while (null != srcdbDoc)
			{
				_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Connector reset - Resetting database last update date for " + srcdbDoc.getItemValue(NCCONST.DITM_DBNAME));
				srcdbDoc.removeItem(NCCONST.DITM_LASTUPDATE);
				srcdbDoc.save(true);
				lotus.domino.Document prevDoc = srcdbDoc;
				srcdbDoc = srcdbView.getNextDocument(prevDoc);
				prevDoc.recycle();
			}
			srcdbView.recycle();
		}
		catch(Exception e)
		{
			_logger.logp(Level.SEVERE, CLASS_NAME, METHOD, "Error resetting connector.");
			_logger.log(Level.SEVERE, CLASS_NAME, e); 
		}
		finally
		{
			ncs.closeNotesSession(ns);
			_logger.exiting(CLASS_NAME, METHOD);
		}
	}

	public void pollDatabases(lotus.domino.Session ns, lotus.domino.Database cdb, int maxDepth) {
		final String METHOD = "pollDatabases";
		_logger.entering(CLASS_NAME, METHOD);
		try
		{
			pollTime = ns.createDateTime("1/1/1900");
			pollTime.setNow();


			templateView = cdb.getView(NCCONST.VIEWTEMPLATES);
			srcdbView = cdb.getView(NCCONST.VIEWDATABASES);
			srcdbView.refresh();
			View vwSubmitQ = cdb.getView(NCCONST.VIEWSUBMITQ);
			View vwCrawlQ = cdb.getView(NCCONST.VIEWCRAWLQ);
			
			//TODO: Make this loop shutdown aware

			lotus.domino.Document srcdbDoc = srcdbView.getFirstDocument();
			while (null != srcdbDoc)
			{
				vwSubmitQ.refresh();
				vwCrawlQ.refresh();
				int qDepth = vwSubmitQ.getEntryCount() + vwCrawlQ.getEntryCount();
				_logger.logp(Level.FINE, CLASS_NAME, METHOD, "Total documents in crawl and submit queues is: " + qDepth);
				if (vwSubmitQ.getEntryCount() + vwCrawlQ.getEntryCount() > maxDepth) {
					_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Queue threshold reached.  Suspending polling. size/max=" +qDepth + "/" + maxDepth);
					srcdbDoc.recycle();
					break;
				}
				_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Source Database Config Document " + srcdbDoc.getItemValue(NCCONST.DITM_DBNAME));
				pollSourceDatabase(ns, cdb, srcdbDoc);
				lotus.domino.Document prevDoc = srcdbDoc;
				srcdbDoc = srcdbView.getNextDocument(prevDoc);
				prevDoc.recycle();
			}
			vwSubmitQ.recycle();
			vwCrawlQ.recycle();
			pollTime.recycle();
			templateView.recycle();
			srcdbView.recycle();
		}
		catch(Exception e)
		{
			_logger.log(Level.SEVERE, CLASS_NAME, e); 
		}
		finally
		{
			_logger.exiting(CLASS_NAME, METHOD);
		}
	}

	public void processRoles (ACL acl, Document dbdoc) throws NotesException {
		final String METHOD = "processRoles";
		_logger.entering(CLASS_NAME, METHOD);
		Vector<String> ExpandedRoleGroups = new Vector<String>();		
		Vector<?> Roles = acl.getRoles();
		_logger.logp(Level.FINER, CLASS_NAME, METHOD, "Roles are " + Roles.toString());
		for (int i=0; i<Roles.size(); i++) {
			String RoleName = Roles.elementAt(i).toString();
			StringBuffer RoleGroups = new StringBuffer();
			RoleGroups.append(RoleName);
			ACLEntry ae = acl.getFirstEntry();
			while (null != ae) {
				int RoleType = ae.getUserType();
				if ((RoleType != ACLEntry.TYPE_SERVER) && (RoleType != ACLEntry.TYPE_SERVER_GROUP)) { 
					if (ae.isRoleEnabled(Roles.elementAt(i).toString())) {
						RoleGroups.append("~~");
						RoleGroups.append(ae.getName().toLowerCase());
						RoleGroups.append("~~");
					}
				}
				ACLEntry prevae = ae;
				ae = acl.getNextEntry(prevae);
				prevae.recycle();				
			}
			// Skip roles with no groups or users assigned
			if(RoleGroups.charAt(RoleGroups.length()-1) != ']')
				ExpandedRoleGroups.add(RoleGroups.toString());
		}
		dbdoc.replaceItemValue(NCCONST.NCITM_ROLEPREFIX, ExpandedRoleGroups);
		_logger.logp(Level.FINER, CLASS_NAME, METHOD, "Roles are " + Roles.toString());
		_logger.exiting(CLASS_NAME, METHOD);
	}
	
	
	public boolean processACL (Database srcdb, Document dbdoc) {
		final String METHOD = "processACL";
		_logger.entering(CLASS_NAME, METHOD);
		try
		{
			// To determine if the ACL has changed we check the log
			String aclActivityText = srcdb.getACLActivityLog().firstElement().toString();
			if (aclActivityText.contentEquals(dbdoc.getItemValueString(NCCONST.DITM_ACLTEXT))) {
				_logger.logp(Level.FINER, CLASS_NAME, METHOD, "ACL has not changed.  Skipping ACL processing. ");
				return false;
			}
			_logger.logp(Level.FINEST, CLASS_NAME, METHOD, "New ACL Text is. " + aclActivityText);			
			dbdoc.replaceItemValue(NCCONST.DITM_ACLTEXT, aclActivityText);		
			ACL acl = srcdb.getACL();			
			// TODO: If we make this a static method and allow the authorization manager to call it
			// we need to consider how the last update will be handled.
			
			Item noAccessUsers = dbdoc.replaceItemValue(NCCONST.NCITM_DBNOACCESSUSERS, null);
			noAccessUsers.setSummary(false);  // None of these need to be appear in views.  
			Item PermitUsers = dbdoc.replaceItemValue(NCCONST.NCITM_DBPERMITUSERS, null);
			PermitUsers.setSummary(false);
			Item PermitGroups = dbdoc.replaceItemValue(NCCONST.NCITM_DBPERMITGROUPS, null);
			PermitGroups.setSummary(false);


			ACLEntry ae = acl.getFirstEntry();
			while (null != ae) {
				_logger.logp(Level.FINER, CLASS_NAME, METHOD, "Checking ACL Entry: " + ae.getName());
				int userType = ae.getUserType();
				// If this is a user explicitly listed with DEPOSITOR or NO ACCESS
				if (ACL.LEVEL_READER > ae.getLevel()){
					// We only need to add people here
					if ((userType == ACLEntry.TYPE_PERSON) || (userType == ACLEntry.TYPE_UNSPECIFIED)) {
						_logger.logp(Level.FINER, CLASS_NAME, METHOD, "Adding the user entry to deny list: " + ae.getName());
						noAccessUsers.appendToTextList(ae.getName().toLowerCase());
					}
				}

				// If this entry has an access level greater than DEPOSITOR
				if (ACL.LEVEL_DEPOSITOR < ae.getLevel()) {
					// Add to the PERMIT USERS if they are a user
					if ((userType == ACLEntry.TYPE_PERSON) || (userType == ACLEntry.TYPE_UNSPECIFIED)) {
						_logger.logp(Level.FINER, CLASS_NAME, METHOD, "Adding the user entry to person allow list: " + ae.getName());
						PermitUsers.appendToTextList(ae.getName().toLowerCase());
					}
					// Add to the PERMIT GROUPS if they are a group
					if  ((userType == ACLEntry.TYPE_MIXED_GROUP) || (userType == ACLEntry.TYPE_PERSON_GROUP) || (userType == ACLEntry.TYPE_UNSPECIFIED)) {
						_logger.logp(Level.FINER, CLASS_NAME, METHOD, "Adding the user entry to group allow list: " + ae.getName());
						PermitGroups.appendToTextList(ae.getName().toLowerCase());
					}
				}
				
				// Now check which roles this ACL entry has
				ACLEntry prevae = ae;
				ae = acl.getNextEntry(prevae);
				prevae.recycle();
			}
			noAccessUsers.recycle();
			PermitUsers.recycle();
			PermitGroups.recycle();
			processRoles(acl,dbdoc);
			acl.recycle();
		}
		catch(Exception e)
		{
			_logger.log(Level.SEVERE, CLASS_NAME, e);
		}
		finally
		{
			_logger.exiting(CLASS_NAME, METHOD);
		}
		return true;
	}

	/*
	*
	* This function should probably return the number of documents queued
	* that way we can prevent overflowing the database
	*
	*/
	
	private void pollSourceDatabase(lotus.domino.Session ns, lotus.domino.Database cdb, lotus.domino.Document srcdbDoc) {
		final String METHOD = "pollSourceDatabase";
		DateTime lastUpdated = null;
		Vector<?> lastUpdatedV = null;
		_logger.entering(CLASS_NAME, METHOD);
		
		try
		{
			// There are configuration options to stop and disable databases
			// In either of these states, we skip processing the database
			if (1 != srcdbDoc.getItemValueInteger(NCCONST.DITM_CRAWLENABLED)) {
				_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Skipping database - Database is DISABLED."); 
				return;
			}
			if (1 == srcdbDoc.getItemValueInteger(NCCONST.DITM_STOPPED)) {
				_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Skipping database - Database is STOPPED."); 
				return;
			}

			// When was this database last updated?
			lastUpdatedV = srcdbDoc.getItemValue(NCCONST.DITM_LASTUPDATE);
			if (0 < lastUpdatedV.size ()) {
				lastUpdated = (DateTime)lastUpdatedV.firstElement();
				_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Last processed time was " + lastUpdated);
			}
			else {
				lastUpdated = ns.createDateTime("1/1/1980");
				_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Database has never been processed.");
			}
			
			// What's our poll interval?
			double pollInterval = srcdbDoc.getItemValueInteger(NCCONST.DITM_UPDATEFREQUENCY);
			double elapsedMinutes = pollTime.timeDifference(lastUpdated)/60;
			_logger.logp(Level.FINE, CLASS_NAME, METHOD, "Time difference is : " + elapsedMinutes);

			// Check poll interval
			if (pollInterval > elapsedMinutes) {
				_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Skipping database - Poll interval has not yet elapsed."); 
				lastUpdated.recycle();
				ns.recycle(lastUpdatedV);
				return;
			}
			// Get modified documents
			lotus.domino.Database srcdb = ns.getDatabase (null, null);
			srcdb.openByReplicaID(srcdbDoc.getItemValueString(NCCONST.DITM_SERVER),srcdbDoc.getItemValueString(NCCONST.DITM_REPLICAID));


			if (processACL(srcdb, srcdbDoc)) {
				// If the ACL has changed and we are using per Document ACLs we need to resend all documents.
				if (srcdbDoc.getItemValueString(NCCONST.DITM_AUTHTYPE).contentEquals(NCCONST.AUTH_ACL)) {
					_logger.logp(Level.INFO, CLASS_NAME, METHOD, "Database ACL has changed - Resetting last update to reindex all document ACLs.");
					lastUpdated = ns.createDateTime("1/1/1980");
				}
			}
			
			// From the template, we get the search string to determine which documents should be processed
			lotus.domino.Document templateDoc = templateView.getDocumentByKey (srcdbDoc.getItemValueString(NCCONST.DITM_TEMPLATE),true);
			String searchString = templateDoc.getItemValueString(NCCONST.TITM_SEARCHSTRING);
			// We append the last processed date as a modifier
			searchString += " & @Modified > [" + lastUpdated + "]";
			_logger.logp(Level.FINE, CLASS_NAME, METHOD, "Search string is: " + searchString); 


			//getDBReaderGroups(srcdb);

			DocumentCollection dc = srcdb.search (searchString, null, 0);
			_logger.logp(Level.INFO, CLASS_NAME, METHOD, srcdb.getFilePath() + " Number of documents to be processed: " + dc.getCount()); 
			lotus.domino.Document curDoc = dc.getFirstDocument();
			while (null != curDoc){		
				String NotesURL = curDoc.getNotesURL();
				_logger.logp(Level.FINER, CLASS_NAME, METHOD, "Processing document " + NotesURL); 
				if (curDoc.hasItem(NCCONST.NCITM_CONFLICT)) {
					_logger.logp(Level.FINER, CLASS_NAME, METHOD, "Skipping conflict document " + NotesURL); 
					lotus.domino.Document prevDoc = curDoc;
					curDoc = dc.getNextDocument(prevDoc);
					prevDoc.recycle();
					continue;
				}
				
				// Create a new crawl request
				lotus.domino.Document crawlRequestDoc = cdb.createDocument();
				crawlRequestDoc.appendItemValue(NCCONST.NCITM_STATE, NCCONST.STATENEW);
				crawlRequestDoc.appendItemValue(NCCONST.ITM_MIMETYPE, NCCONST.DEFAULT_DOCMIMETYPE);
				
				// Create the fields necessary to crawl the document
				crawlRequestDoc.appendItemValue(NCCONST.ITMFORM, NCCONST.FORMCRAWLREQUEST);
				crawlRequestDoc.appendItemValue(NCCONST.NCITM_UNID, curDoc.getUniversalID());
				crawlRequestDoc.appendItemValue(NCCONST.NCITM_REPLICAID, srcdbDoc.getItemValueString(NCCONST.DITM_REPLICAID));
				crawlRequestDoc.appendItemValue(NCCONST.NCITM_SERVER, srcdbDoc.getItemValueString(NCCONST.DITM_SERVER));				
				crawlRequestDoc.appendItemValue(NCCONST.NCITM_TEMPLATE, srcdbDoc.getItemValueString(NCCONST.DITM_TEMPLATE));
				crawlRequestDoc.appendItemValue(NCCONST.NCITM_DOMAIN, srcdbDoc.getItemValueString(NCCONST.DITM_DOMAIN));
				crawlRequestDoc.appendItemValue(NCCONST.NCITM_AUTHTYPE, srcdbDoc.getItemValueString(NCCONST.DITM_AUTHTYPE));

				
				// Map the lock field directly across
				crawlRequestDoc.appendItemValue(NCCONST.ITM_LOCK, srcdbDoc.getItemValueString(NCCONST.DITM_LOCKATTRIBUTE).toLowerCase());
				
				// Add any database level meta data to the document
				crawlRequestDoc.appendItemValue(NCCONST.ITM_GMETAREPLICASERVERS, srcdbDoc.getItemValue(NCCONST.DITM_REPLICASERVERS));
				crawlRequestDoc.appendItemValue(NCCONST.ITM_GMETACATEGORIES, srcdbDoc.getItemValue(NCCONST.DITM_DBCATEGORIES));
				crawlRequestDoc.appendItemValue(NCCONST.ITM_GMETADATABASE, srcdbDoc.getItemValueString(NCCONST.DITM_DBNAME));
				crawlRequestDoc.appendItemValue(NCCONST.ITM_GMETANOTESLINK, NotesURL);

				crawlRequestDoc.save();
				crawlRequestDoc.recycle();  //TEST THIS
				crawlRequestDoc = null;
				lotus.domino.Document prevDoc = curDoc;
				curDoc = dc.getNextDocument(prevDoc);
				prevDoc.recycle();
			}
			dc.recycle();

			// Set last modified date
			srcdbDoc.replaceItemValue(NCCONST.DITM_LASTUPDATE, pollTime);
			srcdbDoc.save();

			// TODO:  Handle db.search for case where there are more that 5000 documents
			// Suspect this limitation is for DIIOP only and not for RPC access
			// Have sucessfully tested up to 9000 documents

			// Recycle our objects
			srcdb.recycle();
			templateDoc.recycle();
			lastUpdated.recycle();
			ns.recycle(lastUpdatedV);
		}
		catch(Exception e)
		{
			_logger.log(Level.SEVERE, CLASS_NAME, e); 
		}
		finally
		{
			_logger.exiting(CLASS_NAME, METHOD);
		}
	}

}