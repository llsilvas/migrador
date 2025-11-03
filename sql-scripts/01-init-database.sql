-- ============================================
-- Script de Inicialização - Migrador IIRGD
-- ============================================
-- Simula estrutura do banco IIRGD para testes
-- Database: IIRGD_TEST

USE master;
GO

-- Criar database se não existir
IF NOT EXISTS (SELECT * FROM sys.databases WHERE name = 'IIRGD_TEST')
BEGIN
    CREATE DATABASE IIRGD_TEST;
    PRINT 'Database IIRGD_TEST created successfully';
END
ELSE
BEGIN
    PRINT 'Database IIRGD_TEST already exists';
END
GO

USE IIRGD_TEST;
GO

-- ============================================
-- Tabela TB_COLETA (simplificada para testes)
-- ============================================
IF NOT EXISTS (SELECT * FROM sys.objects WHERE object_id = OBJECT_ID(N'[dbo].[TB_COLETA]') AND type in (N'U'))
BEGIN
    CREATE TABLE [dbo].[TB_COLETA] (
        [ID] BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        [ID_COLETA_VALID] UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID(),
        [CPF] VARCHAR(11) NULL,
        [RG] VARCHAR(20) NULL,
        [DATA_COLETA] DATETIME2 NOT NULL DEFAULT GETDATE(),
        [TIPO_COLETA] VARCHAR(20) NOT NULL DEFAULT 'DIGITAL',
        [IMAGEM_DIGITAL] VARBINARY(MAX) NULL,
        [CREATED_AT] DATETIME2 NOT NULL DEFAULT GETDATE(),
        [UPDATED_AT] DATETIME2 NULL,

        -- Índices para performance
        CONSTRAINT UK_ID_COLETA_VALID UNIQUE (ID_COLETA_VALID)
    );

    -- Índice para otimizar queries por range de ID (particionamento)
    CREATE NONCLUSTERED INDEX IX_TB_COLETA_ID
        ON [dbo].[TB_COLETA] ([ID] ASC);

    -- Índice para CPF (queries frequentes)
    CREATE NONCLUSTERED INDEX IX_TB_COLETA_CPF
        ON [dbo].[TB_COLETA] ([CPF] ASC)
        WHERE [CPF] IS NOT NULL;

    PRINT 'Table TB_COLETA created successfully with indexes';
END
ELSE
BEGIN
    PRINT 'Table TB_COLETA already exists';
END
GO

PRINT '============================================';
PRINT 'Database initialization completed!';
PRINT 'Ready to populate test data';
PRINT '============================================';
GO
