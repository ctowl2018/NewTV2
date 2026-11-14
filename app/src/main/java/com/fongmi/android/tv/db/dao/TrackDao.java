package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.fongmi.android.tv.bean.Track;

import java.util.List;

@Dao
public abstract class TrackDao extends BaseDao<Track> {

    /** 备份用：导出全部轨道选择记录。 */
    @Query("SELECT * FROM Track")
    public abstract List<Track> findAll();

    @Query("SELECT * FROM Track WHERE `key` = :key")
    public abstract List<Track> find(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract Long insert(Track item);

    @Query("DELETE FROM Track WHERE `key` = :key")
    public abstract void delete(String key);

    @Query("DELETE FROM Track WHERE `key` = :key AND `role` = :role")
    public abstract void delete(String key, int role);
}
