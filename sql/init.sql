-- GuoZhan数据库初始化脚本
-- 用于Docker测试环境的数据库初始化

-- 设置字符集
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS `guozhan` 
CHARACTER SET utf8mb4 
COLLATE utf8mb4_unicode_ci;

-- 使用数据库
USE `guozhan`;

-- 创建用户表
CREATE TABLE IF NOT EXISTS `users` (
    `id` BINARY(16) NOT NULL PRIMARY KEY,
    `username` VARCHAR(16) NOT NULL UNIQUE,
    `country_id` BINARY(16) NULL,
    `rank` INT NOT NULL DEFAULT 0,
    `join_time` BIGINT NOT NULL,
    `last_seen` BIGINT NOT NULL,
    `gold` INT NOT NULL DEFAULT 0,
    `diamond` INT NOT NULL DEFAULT 0,
    INDEX `idx_username` (`username`),
    INDEX `idx_country_id` (`country_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 创建国家表
CREATE TABLE IF NOT EXISTS `countries` (
    `id` BINARY(16) NOT NULL PRIMARY KEY,
    `name` VARCHAR(32) NOT NULL UNIQUE,
    `owner_id` BINARY(16) NOT NULL,
    `creation_time` BIGINT NOT NULL,
    `gold` INT NOT NULL DEFAULT 0,
    `diamond` INT NOT NULL DEFAULT 0,
    `economy_points` INT NOT NULL DEFAULT 0,
    `tax_rate` DECIMAL(3,2) NOT NULL DEFAULT 0.10,
    `core_world` VARCHAR(64) NULL,
    `core_x` INT NULL,
    `core_y` INT NULL,
    `core_z` INT NULL,
    INDEX `idx_name` (`name`),
    INDEX `idx_owner_id` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 创建领土表
CREATE TABLE IF NOT EXISTS `territory_blocks` (
    `x` INT NOT NULL,
    `z` INT NOT NULL,
    `world` VARCHAR(64) NOT NULL,
    `owner_id` BINARY(16) NULL,
    `claim_time` BIGINT NULL,
    `resource_type` VARCHAR(32) NULL,
    `is_capital` BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (`x`, `z`, `world`),
    INDEX `idx_owner_id` (`owner_id`),
    INDEX `idx_world` (`world`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 创建外交关系表
CREATE TABLE IF NOT EXISTS `diplomatic_relations` (
    `id` BINARY(16) NOT NULL PRIMARY KEY,
    `country1_id` BINARY(16) NOT NULL,
    `country2_id` BINARY(16) NOT NULL,
    `relation_type` VARCHAR(16) NOT NULL DEFAULT 'NEUTRAL',
    `friendliness` INT NOT NULL DEFAULT 50,
    `last_updated` BIGINT NOT NULL,
    UNIQUE KEY `unique_relation` (`country1_id`, `country2_id`),
    INDEX `idx_country1` (`country1_id`),
    INDEX `idx_country2` (`country2_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 创建贡献关系表
CREATE TABLE IF NOT EXISTS `tribute_relations` (
    `id` BINARY(16) NOT NULL PRIMARY KEY,
    `tribute_country_id` BINARY(16) NOT NULL,
    `receiving_country_id` BINARY(16) NOT NULL,
    `tribute_rate` DECIMAL(3,2) NOT NULL,
    `creation_time` BIGINT NOT NULL,
    `last_processed` BIGINT NULL,
    UNIQUE KEY `unique_tribute` (`tribute_country_id`, `receiving_country_id`),
    INDEX `idx_tribute_country` (`tribute_country_id`),
    INDEX `idx_receiving_country` (`receiving_country_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 添加外键约束
ALTER TABLE `users` 
ADD CONSTRAINT `fk_users_country` 
FOREIGN KEY (`country_id`) REFERENCES `countries`(`id`) 
ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE `countries` 
ADD CONSTRAINT `fk_countries_owner` 
FOREIGN KEY (`owner_id`) REFERENCES `users`(`id`) 
ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE `territory_blocks` 
ADD CONSTRAINT `fk_territory_owner` 
FOREIGN KEY (`owner_id`) REFERENCES `countries`(`id`) 
ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE `diplomatic_relations` 
ADD CONSTRAINT `fk_diplomatic_country1` 
FOREIGN KEY (`country1_id`) REFERENCES `countries`(`id`) 
ON DELETE CASCADE ON UPDATE CASCADE,
ADD CONSTRAINT `fk_diplomatic_country2` 
FOREIGN KEY (`country2_id`) REFERENCES `countries`(`id`) 
ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE `tribute_relations` 
ADD CONSTRAINT `fk_tribute_tribute_country` 
FOREIGN KEY (`tribute_country_id`) REFERENCES `countries`(`id`) 
ON DELETE CASCADE ON UPDATE CASCADE,
ADD CONSTRAINT `fk_tribute_receiving_country` 
FOREIGN KEY (`receiving_country_id`) REFERENCES `countries`(`id`) 
ON DELETE CASCADE ON UPDATE CASCADE;

-- 插入测试数据（可选）
-- 注意：这里使用的是示例UUID，实际使用时应该生成真实的UUID

-- 测试用户
INSERT IGNORE INTO `users` (`id`, `username`, `country_id`, `rank`, `join_time`, `last_seen`, `gold`, `diamond`) VALUES
(UNHEX(REPLACE('550e8400-e29b-41d4-a716-446655440001', '-', '')), 'TestPlayer1', NULL, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 1000, 100),
(UNHEX(REPLACE('550e8400-e29b-41d4-a716-446655440002', '-', '')), 'TestPlayer2', NULL, 0, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 1000, 100);

-- 测试国家
INSERT IGNORE INTO `countries` (`id`, `name`, `owner_id`, `creation_time`, `gold`, `diamond`, `economy_points`, `tax_rate`) VALUES
(UNHEX(REPLACE('660e8400-e29b-41d4-a716-446655440001', '-', '')), 'TestCountry1', UNHEX(REPLACE('550e8400-e29b-41d4-a716-446655440001', '-', '')), UNIX_TIMESTAMP() * 1000, 5000, 500, 1000, 0.10),
(UNHEX(REPLACE('660e8400-e29b-41d4-a716-446655440002', '-', '')), 'TestCountry2', UNHEX(REPLACE('550e8400-e29b-41d4-a716-446655440002', '-', '')), UNIX_TIMESTAMP() * 1000, 3000, 300, 800, 0.15);

-- 更新用户的国家关联
UPDATE `users` SET `country_id` = UNHEX(REPLACE('660e8400-e29b-41d4-a716-446655440001', '-', '')) WHERE `username` = 'TestPlayer1';
UPDATE `users` SET `country_id` = UNHEX(REPLACE('660e8400-e29b-41d4-a716-446655440002', '-', '')) WHERE `username` = 'TestPlayer2';

-- 创建索引以提高性能
CREATE INDEX IF NOT EXISTS `idx_users_join_time` ON `users` (`join_time`);
CREATE INDEX IF NOT EXISTS `idx_countries_creation_time` ON `countries` (`creation_time`);
CREATE INDEX IF NOT EXISTS `idx_territory_claim_time` ON `territory_blocks` (`claim_time`);
CREATE INDEX IF NOT EXISTS `idx_diplomatic_last_updated` ON `diplomatic_relations` (`last_updated`);

-- 显示创建的表
SHOW TABLES;
