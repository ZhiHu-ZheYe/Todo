/*
 * $Id: ProgressReportingService.java,v 1.2 2011/07/18 00:46:49 trevin Exp trevin $
 * Copyright © 2011 Trevin Beattie
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * $Log: ProgressReportingService.java,v $
 * Revision 1.2  2011/07/18 00:46:49  trevin
 * Added the copyright notice
 *
 * Revision 1.1  2011/05/12 05:06:37  trevin
 * Initial revision
 *
 */
package com.xmission.trevin.android.todo;

/**
 * Methods implemented by all service classes which can report
 * their progress to the binding activity.
 */
public interface ProgressReportingService {

    /** @return the current mode of operation */
    String getCurrentMode();

    /** @return the upper limit of the progress indicator */
    public int getMaxCount();

    /** @return the progress made so far */
    public int getChangedCount();
}
