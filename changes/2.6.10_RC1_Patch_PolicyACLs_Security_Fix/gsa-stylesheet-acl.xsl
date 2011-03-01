<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0" xmlns:dxl="http://www.lotus.com/dxl" exclude-result-prefixes="dxl">
	<xsl:output method="xml" version="1.0" encoding="ISO-8859-1" indent="yes" doctype-public="-//Google//DTD GSA Feeds//EN" doctype-system="gsafeed.dtd"/>
	<xsl:template match="/">
		<gsafeed>
			<header>
				<datasource>domino</datasource>
				<feedtype>incremental</feedtype>
			</header>
			<group>
				<xsl:apply-templates/>
			</group>
		</gsafeed>
	</xsl:template>
	<xsl:template match="dxl:database">
		<xsl:apply-templates select="dxl:document"/>
	</xsl:template>
	<xsl:template match="dxl:document">
		<xsl:variable name="action" select="dxl:item[@name='CS_Action']/dxl:text"/>
		<xsl:variable name="form" select="@form"/>
		<record>
			<xsl:attribute name="url">
				<xsl:value-of select="dxl:item[@name='CS_URL']/dxl:text"/>
			</xsl:attribute>
			<xsl:attribute name="action">
				<xsl:choose>
					<xsl:when test="$action = 'Delete'">
						<xsl:text>delete</xsl:text>
					</xsl:when>
					<xsl:otherwise>
						<xsl:text>add</xsl:text>
					</xsl:otherwise>
				</xsl:choose>
			</xsl:attribute>
			<xsl:attribute name="mimetype">
				<xsl:value-of select="dxl:item[@name='CS_MimeType']/dxl:text"/>
        
			</xsl:attribute>
			<xsl:choose>
				<xsl:when test="$action != 'Delete'">
					<xsl:attribute name="lock">
						<xsl:value-of select="dxl:item[@name='CS_LockAttribute']/dxl:text"/>
					</xsl:attribute>
					<xsl:attribute name="last-modified">
						<xsl:value-of select="dxl:item[@name='CS_RFC822_Modified']/dxl:text"/>
					</xsl:attribute>
					<xsl:attribute name="authmethod">
						<xsl:value-of select="dxl:item[@name='CS_AuthenticationMethod']/dxl:text"/>
					</xsl:attribute>
					<metadata>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>Topic</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:choose>
									<xsl:when test="$form = 'Document'">
										<xsl:value-of select="dxl:item[@name='CS_SearchResultsText']/dxl:text"/>
									</xsl:when>
									<xsl:otherwise>
										<xsl:value-of select="dxl:item[@name='CS_SearchResultsText']/dxl:text"/>
										<xsl:text> [</xsl:text>
										<xsl:value-of select="dxl:item[@name='CS_FileName']/dxl:text"/>
										<xsl:text>]</xsl:text>
									</xsl:otherwise>
								</xsl:choose>
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>Summary</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:value-of select="dxl:item[@name='CS_DocumentDescription']/dxl:text"/>    
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>By</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:value-of select="dxl:item[@name='CS_OriginalDocAuthor']/dxl:text"/>
                
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>On</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:value-of select="dxl:item[@name='CS_RFC822_Created']/dxl:text"/>     
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>Database</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:value-of select="dxl:item[@name='CS_OriginalDatabase']/dxl:text"/>              
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>Categories</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:for-each select="dxl:item[@name='CS_Categories']//dxl:text">
									<xsl:if test="string(.)">
										<xsl:value-of select="(.)"/>               
										<xsl:if test="position() != last()">
											<xsl:text>, </xsl:text>
										</xsl:if>
									</xsl:if>
								</xsl:for-each>
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>Servers</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:for-each select="dxl:item[@name='CS_ReplicaServers']//dxl:text">
									<xsl:if test="string(.)">
										<xsl:value-of select="(.)"/>
										<xsl:if test="position() != last()">
											<xsl:text>, </xsl:text>
										</xsl:if>
									</xsl:if>
								</xsl:for-each>            
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>WebLink</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:choose>
									<xsl:when test="$form = 'Document'">
										<xsl:value-of select="dxl:item[@name='CS_URL']/dxl:text"/>
									</xsl:when>
									<xsl:otherwise>
										<xsl:value-of select="dxl:item[@name='CS_OriginalDocLink']/dxl:text"/>
									</xsl:otherwise>
								</xsl:choose>      
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>NotesLink</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:value-of select="dxl:item[@name='CS_OriginalDocLinkNotes']/dxl:text"/>        
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>DocPath</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:value-of select="dxl:item[@name='CS_OriginalDocLinkWeb']/dxl:text"/>
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>Attachments</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:for-each select="dxl:item[@name='CS_Attachments']//dxl:text">
									<xsl:if test="string(.)">
										<xsl:value-of select="(.)"/>
										<xsl:if test="position() != last()">
											<xsl:text>,</xsl:text>
										</xsl:if>
									</xsl:if>
								</xsl:for-each>       
							</xsl:attribute>
						</meta>
						<meta>
							<xsl:attribute name="name">
								<xsl:text>Form</xsl:text>
							</xsl:attribute>
							<xsl:attribute name="content">
								<xsl:value-of select="$form"/>  
							</xsl:attribute>
						</meta>
            <xsl:for-each select="dxl:item[@name='SomeRandomAttribute']/dxl:text">
              <xsl:if test="(.) != ''">
                <meta>
                  <xsl:attribute name="name">
                    <xsl:text>SomeRandomAttribute</xsl:text>
                  </xsl:attribute>
                  <xsl:attribute name="content">
                    <xsl:value-of select="(.)"/>
                  </xsl:attribute>
                </meta>
              </xsl:if>
            </xsl:for-each>
              <xsl:if test="dxl:item[@name='CS_AuthenticationMethod']/dxl:text != 'none'">
              <xsl:if test="dxl:item[@name='CS_SendACLs']/dxl:text = 'yes'">
                <xsl:for-each select="dxl:item[@name='CS_SecurityTokenUsers']//dxl:text">
                  <xsl:if test="(.) != ''">
                    <meta>
                      <xsl:attribute name="name">
                        <xsl:text>google:aclusers</xsl:text>
                      </xsl:attribute>
                      <xsl:attribute name="content">
                        <xsl:value-of select="(.)"/>
                      </xsl:attribute>
                    </meta>
                  </xsl:if>
                </xsl:for-each>
                <xsl:for-each select="dxl:item[@name='CS_SecurityTokenGroups']//dxl:text">
                  <xsl:if test="(.) != ''">
                    <meta>
                      <xsl:attribute name="name">
                        <xsl:text>google:aclgroups</xsl:text>
                      </xsl:attribute>
                      <xsl:attribute name="content">
                        <xsl:value-of select="(.)"/>
                      </xsl:attribute>
                    </meta>
                  </xsl:if>
                </xsl:for-each>
              </xsl:if>
            </xsl:if>
					</metadata>
					<xsl:choose>
						<xsl:when test="$form = 'Document'">
							<content>
								<xsl:for-each select="dxl:item[@name='CS_Content']//dxl:text">
									<xsl:if test="string(.)">
										<xsl:value-of select="(.)"/>
										<xsl:if test="position() != last()">
											<xsl:text>, </xsl:text>
										</xsl:if>
									</xsl:if>
								</xsl:for-each> 
							</content>
						</xsl:when>
						<xsl:otherwise>
							<xsl:for-each select='dxl:item[@name="$FILE"]'>
								<content encoding="base64binary">
									<xsl:value-of select="descendant::dxl:filedata"/>   
								</content>
							</xsl:for-each>
						</xsl:otherwise>
					</xsl:choose>
				</xsl:when>
			</xsl:choose>
		</record>
	</xsl:template>
</xsl:stylesheet>