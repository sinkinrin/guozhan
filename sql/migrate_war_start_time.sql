-- GuoZhan 战争系统修复 - 数据库迁移脚本
-- 🔧 修复问题2：添加战争开始时间字段到外交关系表

-- 设置字符集
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- 使用数据库
USE `guozhan`;

-- 1. 添加 war_start_time 字段到 gz_diplomatic_relations 表
ALTER TABLE `gz_diplomatic_relations` 
ADD COLUMN `war_start_time` BIGINT NULL COMMENT '战争开始时间戳（毫秒）' AFTER `updated_at`;

-- 2. 为现有的战争状态关系设置战争开始时间（使用 updated_at 作为回退值）
UPDATE `gz_diplomatic_relations` 
SET `war_start_time` = `updated_at` 
WHERE `relation_type` = 'WAR' AND `war_start_time` IS NULL;

-- 3. 创建索引以提高查询性能
CREATE INDEX IF NOT EXISTS `idx_war_start_time` ON `gz_diplomatic_relations` (`war_start_time`);

-- 4. 验证字段添加成功
SELECT 
    COLUMN_NAME,
    DATA_TYPE,
    IS_NULLABLE,
    COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'gz_diplomatic_relations'
  AND COLUMN_NAME = 'war_start_time';

-- 5. 显示迁移结果
SELECT '迁移完成：已添加 war_start_time 字段到 gz_diplomatic_relations 表' AS status;

-- 6. 显示当前所有战争状态的关系
SELECT 
    country1_id,
    country2_id,
    relation_type,
    war_start_time,
    FROM_UNIXTIME(war_start_time/1000) AS war_start_datetime
FROM `gz_diplomatic_relations`
WHERE `relation_type` = 'WAR';

