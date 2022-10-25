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
package org.xwiki.panels;

import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.wiki.internal.DefaultWikiComponentManager;
import org.xwiki.component.wiki.internal.DefaultWikiComponentManagerContext;
import org.xwiki.component.wiki.internal.WikiComponentManagerEventListenerHelper;
import org.xwiki.component.wiki.internal.bridge.DefaultContentParser;
import org.xwiki.component.wiki.internal.bridge.DefaultWikiObjectComponentManagerEventListener;
import org.xwiki.component.wiki.internal.bridge.WikiObjectComponentManagerEventListenerProxy;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.observation.EventListener;
import org.xwiki.rendering.async.internal.block.DefaultBlockAsyncRenderer;
import org.xwiki.rendering.internal.configuration.DefaultExtendedRenderingConfiguration;
import org.xwiki.rendering.internal.configuration.RenderingConfigClassDocumentConfigurationSource;
import org.xwiki.rendering.internal.macro.DefaultMacroCategoryManager;
import org.xwiki.rendering.internal.syntax.SyntaxConverter;
import org.xwiki.rendering.internal.transformation.macro.DefaultMacroTransformationConfiguration;
import org.xwiki.rendering.script.RenderingScriptService;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.page.HTML50ComponentList;
import org.xwiki.test.page.PageTest;
import org.xwiki.test.page.TestNoScriptMacro;
import org.xwiki.test.page.XWikiSyntax21ComponentList;
import org.xwiki.uiextension.internal.DefaultUIExtensionManager;
import org.xwiki.uiextension.internal.WikiUIExtensionComponentBuilder;
import org.xwiki.uiextension.script.UIExtensionScriptService;
import org.xwiki.xml.internal.html.filter.ControlCharactersFilter;

import com.xpn.xwiki.doc.XWikiDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.xwiki.component.wiki.internal.bridge.DefaultWikiObjectComponentManagerEventListener.EVENT_LISTENER_NAME;

/**
 * Test of {@code Panels.IncludedPagesDocumentInformation}.
 *
 * @version $Id$
 * @since 14.10RC1
 * @since 14.4.7
 */
@XWikiSyntax21ComponentList
@HTML50ComponentList
@ComponentList({
    UIExtensionScriptService.class,
    DefaultUIExtensionManager.class,
    DefaultWikiObjectComponentManagerEventListener.class,
    WikiObjectComponentManagerEventListenerProxy.class,
    WikiComponentManagerEventListenerHelper.class,
    DefaultWikiComponentManager.class,
    DefaultWikiComponentManagerContext.class,
    WikiUIExtensionComponentBuilder.class,
    DefaultContentParser.class,
    // Start - Required for RenderingScriptService
    RenderingScriptService.class,
    SyntaxConverter.class,
    DefaultMacroCategoryManager.class,
    DefaultMacroTransformationConfiguration.class,
    DefaultExtendedRenderingConfiguration.class,
    RenderingConfigClassDocumentConfigurationSource.class,
    // End - Required for RenderingScriptService
    DefaultBlockAsyncRenderer.class,
    TestNoScriptMacro.class,
    ControlCharactersFilter.class
})
class IncludedPagesDocumentInformationPageTest extends PageTest
{
    @Test
    void render() throws Exception
    {
        // Load the UIX.
        XWikiDocument includedPagesDoc =
            loadPage(new DocumentReference("xwiki", "Panels", "IncludedPagesDocumentInformation"));
        this.componentManager.registerComponent(ComponentManager.class, "wiki", this.componentManager);
        AuthorizationManager authorizationManager =
            this.componentManager.registerMockComponent(AuthorizationManager.class);
        when(authorizationManager.hasAccess(any(), any(), any())).thenReturn(true);
        // The listeners are not registered by default. We trigger it manually so that the UIX is registered and can be
        // found and rendered.
        this.componentManager.<EventListener>getInstance(EventListener.class, EVENT_LISTENER_NAME)
            .onEvent(new DocumentCreatedEvent(), includedPagesDoc, null);

        // We initialize a document with an include macro, so that the UIX has something to render. 
        this.context.setDoc(initDocWithInclude());
        // Create the UIXP so that the UIX can be rendered.
        Document document = renderHTMLPage(initUIXPDoc());
        Elements as = document.select("a");
        assertEquals("XWiki.{{noscript}}\">]]<strong>bold</strong>\"", as.get(0).attr("href"));
        assertEquals("path:/xwiki/bin/edit/XWiki/%7B%7Bnoscript%7D%7D%22%3E%5D%5D%3Cstrong%3Ebold%3C%2Fstrong%3E%22",
            as.get(1).attr("href"));
        assertEquals("panels.documentInformation.editIncluded [XWiki.{{noscript}}\">]]<strong>bold</strong>\"]",
            as.get(1).selectFirst("img").attr("alt"));
    }

    private XWikiDocument initDocWithInclude()
    {
        XWikiDocument document = new XWikiDocument(new DocumentReference("xwiki", "XWiki", "PageWithReference"));
        document.setSyntax(Syntax.XWIKI_2_0);
        document.setContent("{{display reference=\"{{noscript~}~}~\">]]<strong>bold</strong>~\"\"/}}");
        return document;
    }

    private static XWikiDocument initUIXPDoc()
    {
        XWikiDocument document = new XWikiDocument(new DocumentReference("xwiki", "XWiki", "Test"));
        document.setSyntax(Syntax.XWIKI_2_0);
        document.setContent("{{velocity}}\n"
            + "{{html}}\n"
            + "#foreach ($extension in $services.uix.getExtensions('org.xwiki.platform.panels.documentInformation'))\n"
            + "  $services.rendering.render($extension.execute(), 'html/5.0')\n"
            + "#end\n"
            + "{{/html}}"
            + "{{/velocity}}");
        return document;
    }
}