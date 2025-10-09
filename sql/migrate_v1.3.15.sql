-- GuoZhan v1.3.15 数据库迁移脚本
-- 修复王城所有权和数据库架构问题

-- 1. 修复现有城市的所有权问题
-- 为所有作为首都的城市设置正确的所有者
UPDATE gz_cities 
SET owner = (
    SELECT id 
    FROM gz_countries 
    WHERE gz_countries.capital = gz_cities.id
)
WHERE gz_cities.id IN (
    SELECT capital 
    FROM gz_countries
);

-- 2. 验证修复结果
-- 检查是否还有未设置所有者的首都城市
SELECT 
    c.name as country_name,
    ci.id as city_id,
    ci.x,
    ci.z,
    ci.owner
FROM gz_countries c
LEFT JOIN gz_cities ci ON c.capital = ci.id
WHERE ci.owner IS NULL;

-- 3. 添加数据完整性约束（可选，谨慎使用）
-- ALTER TABLE gz_cities ADD CONSTRAINT fk_city_owner 
-- FOREIGN KEY (owner) REFERENCES gz_countries(id) ON DELETE SET NULL;

-- 4. 创建索引以提高查询性能
CREATE INDEX IF NOT EXISTS idx_gz_cities_owner ON gz_cities(owner);

-- 5. 记录迁移完成
INSERT INTO gz_migration_log (version, applied_at, description) 
VALUES ('v1.3.15', NOW(), 'Fixed capital city ownership and database schema issues')
ON DUPLICATE KEY UPDATE applied_at = NOW();

-- 创建迁移日志表（如果不存在）
CREATE TABLE IF NOT EXISTS gz_migration_log (
    version VARCHAR(20) PRIMARY KEY,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description TEXT
);
