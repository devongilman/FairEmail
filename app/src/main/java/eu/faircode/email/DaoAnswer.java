package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2021 by Marcel Bokhorst (M66B)
*/

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface DaoAnswer {
    @Query("SELECT * FROM answer" +
            " WHERE :all OR NOT hide" +
            " ORDER BY -favorite, name COLLATE NOCASE")
    List<EntityAnswer> getAnswers(boolean all);

    @Query("SELECT * FROM answer" +
            " WHERE favorite = :favorite" +
            " AND NOT hide" +
            " ORDER BY name COLLATE NOCASE")
    List<EntityAnswer> getAnswersByFavorite(boolean favorite);

    @Query("SELECT * FROM answer WHERE id = :id")
    EntityAnswer getAnswer(long id);

    @Query("SELECT * FROM answer" +
            " WHERE standard AND NOT hide")
    EntityAnswer getStandardAnswer();

    @Query("SELECT * FROM answer" +
            " ORDER BY -favorite, name COLLATE NOCASE")
    LiveData<List<EntityAnswer>> liveAnswers();

    @Query("SELECT COUNT(*) FROM answer" +
            " WHERE NOT hide")
    Integer getAnswerCount();

    @Insert
    long insertAnswer(EntityAnswer answer);

    @Update
    int updateAnswer(EntityAnswer answer);

    @Query("UPDATE answer SET hide = :hide WHERE id = :id AND NOT (hide IS :hide)")
    int setAnswerHidden(long id, boolean hide);

    @Query("UPDATE answer SET standard = 0 WHERE NOT (standard IS 0)")
    void resetStandard();

    @Query("DELETE FROM answer WHERE id = :id")
    void deleteAnswer(long id);
}
