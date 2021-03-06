// Copyright 2011 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.notes.client.mock;

import com.google.common.primitives.Ints;
import com.google.enterprise.connector.notes.client.NotesDateTime;
import com.google.enterprise.connector.spi.RepositoryException;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.logging.Logger;

public class NotesDateTimeMock extends NotesBaseMock
    implements NotesDateTime {
  private static final String CLASS_NAME = NotesDateTimeMock.class.getName();
  private static final SimpleDateFormat FORMATTER =
      new SimpleDateFormat("MM/dd/yyyy hh:mm:ss z");

  /** The logger for this class. */
  private static final Logger LOGGER =
      Logger.getLogger(CLASS_NAME);

  private Date date;

  NotesDateTimeMock() {
  }

  public NotesDateTimeMock(Date date) {
    this.date = date;
  }

  /** {@inheritDoc} */
  @Override
  public void adjustSecond(int seconds) throws RepositoryException {
    Calendar cal = Calendar.getInstance();
    cal.setTime(date);
    cal.roll(Calendar.SECOND, seconds);
    date = cal.getTime();
  }

  /** {@inheritDoc} */
  @Override
  public Date toJavaDate() throws RepositoryException {
   LOGGER.entering(CLASS_NAME, "");
   return date;
  }

  /** {@inheritDoc} */
  @Override
  public void setNow() throws RepositoryException {
   LOGGER.entering(CLASS_NAME, "setNow");
   date = new Date();
  }

  /** {@inheritDoc} */
  @Override
  public void setAnyTime() throws RepositoryException {
   LOGGER.entering(CLASS_NAME, "setAnyTime");
   // empty
  }

  /** {@inheritDoc} */
  @Override
  public int timeDifference(NotesDateTime otherDateTime)
      throws RepositoryException, IllegalArgumentException {
   LOGGER.entering(CLASS_NAME, "timeDifference");
   Calendar thisDate = Calendar.getInstance();
   thisDate.setTime(date);
   Calendar otherDate = Calendar.getInstance();
   otherDate.setTime(otherDateTime.toJavaDate());
   return Ints.checkedCast(
       (thisDate.getTimeInMillis() - otherDate.getTimeInMillis()) / 1000L);
  }

  @Override
  public String toString() {
    try {
      return FORMATTER.format(date);
    } catch (Exception e) {
      return "";
    }
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((date == null) ? 0 : date.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null || date == null) {
      return false;
    }
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof NotesDateTimeMock)) {
      return false;
    }
    return date.equals(((NotesDateTimeMock) obj).date);
  }
}
