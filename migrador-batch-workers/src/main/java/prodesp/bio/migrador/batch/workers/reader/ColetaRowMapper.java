package prodesp.bio.migrador.batch.workers.reader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import prodesp.bio.migrador.core.domain.model.ColetaEntity;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * RowMapper para converter ResultSet SQL em ColetaEntity.
 *
 * Mapeia as colunas da tabela TB_COLETA para os campos da entidade.
 */
@Slf4j
public class ColetaRowMapper implements RowMapper<ColetaEntity> {

    @Override
    public ColetaEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            return ColetaEntity.builder()
                    .id(rs.getLong("ID"))
                    .idColetaValid(rs.getString("ID_COLETA_VALID"))
                    .cpf(rs.getString("CPF"))
                    .rg(rs.getString("RG"))
                    .dataColeta(rs.getTimestamp("DATA_COLETA") != null
                            ? rs.getTimestamp("DATA_COLETA").toLocalDateTime()
                            : null)
                    .tipoColeta(rs.getString("TIPO_COLETA"))
                    // Imagem pode ser grande - carregar apenas se necessário
                    .imagemDigital(rs.getBytes("IMAGEM_DIGITAL"))
                    .build();

        } catch (SQLException e) {
            log.error("Error mapping row {} from ResultSet: {}", rowNum, e.getMessage(), e);
            throw e;
        }
    }
}
