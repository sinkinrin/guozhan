-- GuoZhan v1.3.19 数据库迁移脚本
-- 添加国家宣言字段

-- 1. 添加 declaration 字段到 gz_countries 表
ALTER TABLE `gz_countries` 
ADD COLUMN `declaration` TEXT NULL COMMENT '国家宣言' AFTER `economy_points`;

-- 2. 验证字段添加成功
SELECT 
    COLUMN_NAME,
    DATA_TYPE,
    IS_NULLABLE,
    COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'gz_countries'
  AND COLUMN_NAME = 'declaration';

-- 3. 记录迁移完成
INSERT INTO gz_migration_log (version, applied_at, description) 
VALUES ('v1.3.19', NOW(), 'Added declaration field to countries table')
ON DUPLICATE KEY UPDATE applied_at = NOW();

-- 4. 显示迁移结果
SELECT '迁移完成：已添加 declaration 字段到 gz_countries 表' AS status;

