/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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
 */
package org.xwiki.extension.security.internal;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.apache.solr.common.SolrDocument;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.extension.ExtensionId;
import org.xwiki.extension.InstalledExtension;
import org.xwiki.extension.index.internal.ExtensionIndexStore;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.search.solr.SolrUtils;
import org.xwiki.search.solr.internal.api.FieldUtils;

import static java.util.Map.entry;
import static java.util.Map.ofEntries;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang.StringEscapeUtils.escapeXml;
import static org.xwiki.extension.index.internal.ExtensionIndexSolrCoreInitializer.SECURITY_ADVICE;
import static org.xwiki.extension.index.internal.ExtensionIndexSolrCoreInitializer.SECURITY_CVE_CVSS;
import static org.xwiki.extension.index.internal.ExtensionIndexSolrCoreInitializer.SECURITY_CVE_ID;
import static org.xwiki.extension.index.internal.ExtensionIndexSolrCoreInitializer.SECURITY_CVE_LINK;
import static org.xwiki.extension.index.internal.ExtensionIndexSolrCoreInitializer.SECURITY_FIX_VERSION;
import static org.xwiki.extension.index.internal.ExtensionIndexSolrCoreInitializer.SECURITY_MAX_CVSS;
import static org.xwiki.extension.index.internal.ExtensionIndexSolrCoreInitializer.SOLR_FIELD_ID;
import static org.xwiki.extension.security.internal.livedata.ExtensionSecurityLiveDataConfigurationProvider.ADVICE;
import static org.xwiki.extension.security.internal.livedata.ExtensionSecurityLiveDataConfigurationProvider.CVE_ID;
import static org.xwiki.extension.security.internal.livedata.ExtensionSecurityLiveDataConfigurationProvider.EXTENSION_ID;
import static org.xwiki.extension.security.internal.livedata.ExtensionSecurityLiveDataConfigurationProvider.FIX_VERSION;
import static org.xwiki.extension.security.internal.livedata.ExtensionSecurityLiveDataConfigurationProvider.MAX_CVSS;
import static org.xwiki.extension.security.internal.livedata.ExtensionSecurityLiveDataConfigurationProvider.NAME;
import static org.xwiki.extension.security.internal.livedata.ExtensionSecurityLiveDataConfigurationProvider.WIKIS;

/**
 * Converts a {@link SolrDocument} to a {@link Map} of Live Data entries.
 *
 * @version $Id$
 * @since 15.5RC1
 */
@Component(roles = SolrToLiveDataEntryMapper.class)
@Singleton
public class SolrToLiveDataEntryMapper
{
    private static final String LINK_TEMPLATE = "<a href='%s'>%s</a>";

    @Inject
    private SolrUtils solrUtils;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private ContextualLocalizationManager l10n;

    @Inject
    private ExtensionIndexStore extensionIndexStore;

    /**
     * @param doc the document to convert to Live Data entries.
     * @return Converts a {@link SolrDocument} to a {@link Map} of Live Data entries.
     */
    public Map<String, Object> mapDocToEntries(SolrDocument doc)
    {
        return ofEntries(
            entry(NAME, buildExtensionName(doc)),
            entry(EXTENSION_ID, buildExtensionId(doc)),
            entry(MAX_CVSS, buildMaxCVSS(doc)),
            entry(CVE_ID, buildCVEList(doc)),
            entry(FIX_VERSION, buildFixVersion(doc)),
            entry(ADVICE, buildAdvice(doc)),
            entry(WIKIS, buildWikis(doc))
        );
    }

    private static String buildCVEList(SolrDocument doc)
    {
        List<Object> cveIds = new ArrayList<>(doc.getFieldValues(SECURITY_CVE_ID));
        List<Object> cveLinks = new ArrayList<>(doc.getFieldValues(SECURITY_CVE_LINK));
        List<Object> cveCVSS = new ArrayList<>(doc.getFieldValues(SECURITY_CVE_CVSS));

        return IntStream.range(0, cveIds.size())
            .mapToObj(value -> String.format("<a href='%s'>%s</a>&nbsp;(%s)",
                escapeXml(String.valueOf(cveLinks.get(value))),
                escapeXml(String.valueOf(cveIds.get(value))),
                escapeXml(String.valueOf(cveCVSS.get(value)))))
            .collect(joining("<br/>"));
    }

    private String buildAdvice(SolrDocument doc)
    {
        String extensionManagerLink = getExtensionManagerLink(this.extensionIndexStore.getExtensionId(doc));
        return this.l10n.getTranslationPlain(this.solrUtils.get(SECURITY_ADVICE, doc),
            String.format(LINK_TEMPLATE, escapeXml(extensionManagerLink),
                escapeXml(this.l10n.getTranslationPlain("extension.security.analysis.advice.upgradeFromEM.0"))));
    }

    private String buildFixVersion(SolrDocument doc)
    {
        return this.solrUtils.get(SECURITY_FIX_VERSION, doc);
    }

    private Double buildMaxCVSS(SolrDocument doc)
    {
        return this.solrUtils.get(SECURITY_MAX_CVSS, doc);
    }

    private String buildExtensionId(SolrDocument doc)
    {
        return this.solrUtils.get(SOLR_FIELD_ID, doc);
    }

    private String buildExtensionName(SolrDocument solrDoc)
    {
        String extensionName;
        // If the extension does not have a name and version indexed, we fall back to an extensionId parsing.
        // Note: this is not supposed to happen in practice.
        ExtensionId extensionId = this.extensionIndexStore.getExtensionId(solrDoc);
        if (solrDoc.get(FieldUtils.NAME) != null) {
            extensionName = this.solrUtils.get(FieldUtils.NAME, solrDoc);
        } else {
            // Fallback to the id in case the name is empty.
            String[] versionId = this.solrUtils.getId(solrDoc).split("/");
            extensionName = versionId[0];
        }
        String url = getExtensionManagerLink(extensionId);

        return String.format(LINK_TEMPLATE, escapeXml(url), escapeXml(String.valueOf(extensionName)));
    }

    private String buildWikis(SolrDocument doc)
    {
        List<Object> list = this.solrUtils.getList(InstalledExtension.FIELD_INSTALLED_NAMESPACES, doc);
        if (list == null) {
            return "";
        }
        return list.stream()
            .map(String::valueOf)
            .map(it -> it.replaceFirst("wiki:", ""))
            .filter(it -> !Objects.equals(it, "{root}"))
            .collect(joining(", "));
    }

    /**
     * Returns the link to the extension manager for a given extension.
     *
     * @param extensionId the ID of the extension
     * @return the link to the extension in the extension manager
     */
    private String getExtensionManagerLink(ExtensionId extensionId)
    {
        List<BasicNameValuePair> parameters = List.of(
            new BasicNameValuePair("section", "XWiki.Extensions"),
            new BasicNameValuePair("extensionId", extensionId.getId()),
            new BasicNameValuePair("extensionVersion", extensionId.getVersion().getValue())
        );
        return this.documentAccessBridge.getDocumentURL(new LocalDocumentReference("XWiki", "XWikiPreferences"),
            "admin", URLEncodedUtils.format(parameters, Charset.defaultCharset()), null);
    }
}
