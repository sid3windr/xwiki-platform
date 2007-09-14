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
 *
 */
package org.xwiki.action;

import org.xwiki.container.Container;

public interface ActionManager
{
    String ROLE = ActionManager.class.getName();

    /**
     * @exception ActionException when we haven't been able to use the error action to handle
     *            the original exception
     */
    void handleRequest(Container container) throws ActionException;

    /**
     * Force execution of a specific action.
     * @exception ActionException when we haven't been able to use the error action to handle
     *            the original exception
     */
    void handleRequest(Container container, String actionName) throws ActionException;

    void handleRequest(Container container, String actionName, Object additionalData)
        throws ActionException;
}
