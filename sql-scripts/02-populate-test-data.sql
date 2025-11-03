-- ============================================
-- Popular Dados de Teste - Migrador IIRGD
-- ============================================
-- Insere registros de teste para validar migração
-- Performance: ~100k registros em poucos segundos

USE IIRGD_TEST;
GO

SET NOCOUNT ON;
GO

PRINT '============================================';
PRINT 'Starting test data population...';
PRINT '============================================';
GO

-- Verificar quantos registros já existem
DECLARE @ExistingCount INT;
SELECT @ExistingCount = COUNT(*) FROM [dbo].[TB_COLETA];

IF @ExistingCount > 0
BEGIN
    PRINT 'WARNING: Table TB_COLETA already has ' + CAST(@ExistingCount AS VARCHAR) + ' records';
    PRINT 'Skipping data population to avoid duplicates';
END
ELSE
BEGIN
    PRINT 'Populating TB_COLETA with test data...';

    -- ============================================
    -- BATCH 1: Inserir 100.000 registros
    -- ============================================
    DECLARE @BatchSize INT = 1000;
    DECLARE @TotalRecords INT = 500000;
    DECLARE @Counter INT = 0;
    DECLARE @StartTime DATETIME2 = GETDATE();

    WHILE @Counter < @TotalRecords
    BEGIN
        -- Inserir batch de 1000 registros
        INSERT INTO [dbo].[TB_COLETA]
            ([ID_COLETA_VALID], [CPF], [RG], [DATA_COLETA], [TIPO_COLETA], [IMAGEM_DIGITAL])
        SELECT TOP (@BatchSize)
            NEWID() AS ID_COLETA_VALID,
            -- CPF: formato 11 dígitos (pode ter alguns nulos)
            CASE
                WHEN (ABS(CHECKSUM(NEWID())) % 10) < 9
                THEN RIGHT('00000000000' + CAST(ABS(CHECKSUM(NEWID())) % 100000000000 AS VARCHAR), 11)
                ELSE NULL
            END AS CPF,
            -- RG: formato variado
            'RG-' + RIGHT('00000000' + CAST(ABS(CHECKSUM(NEWID())) % 100000000 AS VARCHAR), 8) AS RG,
            -- Data coleta: últimos 365 dias
            DATEADD(DAY, -(ABS(CHECKSUM(NEWID())) % 365), GETDATE()) AS DATA_COLETA,
            -- Tipo coleta: DIGITAL, PRESENCIAL, etc
            CASE (ABS(CHECKSUM(NEWID())) % 3)
                WHEN 0 THEN 'DIGITAL'
                WHEN 1 THEN 'PRESENCIAL'
                ELSE 'AUTOMATICO'
            END AS TIPO_COLETA,
            -- Imagem: NULL para economizar espaço (simular)
            NULL AS IMAGEM_DIGITAL
        FROM sys.all_objects a
        CROSS JOIN sys.all_objects b;

        SET @Counter = @Counter + @BatchSize;

        -- Log progresso a cada 10k registros
        IF @Counter % 10000 = 0
        BEGIN
            DECLARE @ElapsedSeconds INT = DATEDIFF(SECOND, @StartTime, GETDATE());
            DECLARE @RecordsPerSecond INT = CASE WHEN @ElapsedSeconds > 0 THEN @Counter / @ElapsedSeconds ELSE 0 END;

            PRINT 'Inserted: ' + CAST(@Counter AS VARCHAR) + ' / ' + CAST(@TotalRecords AS VARCHAR) +
                  ' records (' + CAST(@RecordsPerSecond AS VARCHAR) + ' records/sec)';
        END
    END

    -- Estatísticas finais
    DECLARE @FinalCount INT;
    SELECT @FinalCount = COUNT(*) FROM [dbo].[TB_COLETA];

    DECLARE @TotalTime INT = DATEDIFF(SECOND, @StartTime, GETDATE());
    DECLARE @AvgSpeed INT = CASE WHEN @TotalTime > 0 THEN @FinalCount / @TotalTime ELSE 0 END;

    PRINT '============================================';
    PRINT 'Data population completed!';
    PRINT 'Total records inserted: ' + CAST(@FinalCount AS VARCHAR);
    PRINT 'Time elapsed: ' + CAST(@TotalTime AS VARCHAR) + ' seconds';
    PRINT 'Average speed: ' + CAST(@AvgSpeed AS VARCHAR) + ' records/sec';
    PRINT '============================================';

    -- Estatísticas da tabela
    PRINT '';
    PRINT 'Table statistics:';
    SELECT
        COUNT(*) AS TotalRecords,
        COUNT(DISTINCT ID_COLETA_VALID) AS UniqueColetas,
        COUNT(CPF) AS RecordsWithCPF,
        COUNT(DISTINCT CPF) AS UniqueCPFs,
        MIN(ID) AS MinID,
        MAX(ID) AS MaxID,
        MIN(DATA_COLETA) AS OldestColeta,
        MAX(DATA_COLETA) AS NewestColeta
    FROM [dbo].[TB_COLETA];
END
GO

-- Atualizar estatísticas para otimizar queries
UPDATE STATISTICS [dbo].[TB_COLETA];
GO

PRINT '';
PRINT 'Database ready for migration tests!';
PRINT 'Connection string: jdbc:sqlserver://localhost:1433;databaseName=IIRGD_TEST;encrypt=false';
PRINT 'Username: sa';
PRINT 'Password: Migrador@2025!Strong';
GO
