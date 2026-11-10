package com.fongmi.android.tv.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

public class Migrations {

    public static final Migration MIGRATION_30_31 = new Migration(30, 31) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL, `adaptive` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_31_32 = new Migration(31, 32) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE History_Backup (`key` TEXT NOT NULL, `vodPic` TEXT, `vodName` TEXT, `vodFlag` TEXT, `vodRemarks` TEXT, `episodeUrl` TEXT, `revSort` INTEGER NOT NULL, `revPlay` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `opening` INTEGER NOT NULL, `ending` INTEGER NOT NULL, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `speed` REAL NOT NULL, `scale` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`key`))");
            database.execSQL("INSERT INTO History_Backup SELECT `key`, `vodPic`, `vodName`, `vodFlag`, `vodRemarks`, `episodeUrl`, `revSort`, `revPlay`, `createTime`, `opening`, `ending`, `position`, `duration`, `speed`, `scale`, `cid` FROM History");
            database.execSQL("DROP TABLE History");
            database.execSQL("ALTER TABLE History_Backup RENAME to History");
        }
    };

    public static final Migration MIGRATION_32_33 = new Migration(32, 33) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE Live ADD COLUMN keep TEXT DEFAULT NULL");
        }
    };

    public static final Migration MIGRATION_33_34 = new Migration(33, 34) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, `track` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_34_35 = new Migration(34, 35) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `format` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type` ON `Track` (`key`, `type`)");
        }
    };

    public static final Migration MIGRATION_35_36 = new Migration(35, 36) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE Track");
            database.execSQL("CREATE TABLE Track (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` INTEGER NOT NULL, `role` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `key` TEXT, `name` TEXT, `format` TEXT, `label` TEXT, `language` TEXT, `mimeType` TEXT, `selected` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Track_key_type_role` ON `Track` (`key`, `type`, `role`)");
        }
    };

    /**
     * 片尾口径由「尾长」改为「位置」：把历史里的旧尾长值换算成片尾点位置。
     *
     * <p>旧语义 {@code ending = duration − position}（剩余尾长）；新语义 {@code ending = 片尾点位置}
     * （与片头 opening 一致，显示什么就跳到哪）。正常设置的片尾点都落在后段
     * （{@code position >= duration − opEdLimit}，opEdLimit ≤ 10 分钟），而旧尾长 ≤ opEdLimit，
     * 因此用 {@code ending < duration / 2} 判定旧尾长足够稳妥：命中则换算成 {@code duration − ending}。
     */
    public static final Migration MIGRATION_36_37 = new Migration(36, 37) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("UPDATE History SET ending = duration - ending WHERE ending > 0 AND duration > 0 AND ending < duration / 2");
        }
    };

    /**
     * 直播功能移除：删除 Live 表，点播的 History/Keep/Config/Site/Track/Device 全部保留。
     * 用显式迁移而非 destructive migration，避免老用户升级时点播观看记录、收藏与站点配置被清空。
     */
    public static final Migration MIGRATION_37_38 = new Migration(37, 38) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("DROP TABLE IF EXISTS Live");
        }
    };
}
