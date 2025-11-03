/**
 * Copyright (c) 2018 Prodesp Tecnologia da Informação
 */
package prodesp.bio.migrador.core.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 *
 * @author José Marcelo S. Fascio <jmarcelo@sp.gov.br>
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties(ignoreUnknown = true)
public class TemplateMetadata implements Serializable {

    @Serial
    private static final long serialVersionUID = 8425816252774861253L;
    
    private String local;
    
    private String finalidade;
    
    private String idColeta;
    
    private String idObjeto;
    
    private String tipo;
    
    private Integer tamanho;
    
    private Integer qualidade;
    
    private Integer quantidadeMinucias;
    
    private Date dataInclusao;
    
    
    public String getLocal() {
        return local;
    }

    public void setLocal(String local) {
        this.local = local;
    }

    public String getFinalidade() {
        return finalidade;
    }

    public void setFinalidade(String finalidade) {
        this.finalidade = finalidade;
    }

    public String getIdColeta() {
        return idColeta;
    }

    public void setIdColeta(String idColeta) {
        this.idColeta = idColeta;
    }

    public String getIdObjeto() {
        return idObjeto;
    }

    public void setIdObjeto(String idObjeto) {
        this.idObjeto = idObjeto;
    }

    public String getTipo() {
        return tipo;
    }

    public void setTipo(String tipo) {
        this.tipo = tipo;
    }
    
    public Integer getQualidade() {
        return qualidade;
    }

    public void setQualidade(Integer qualidade) {
        this.qualidade = qualidade;
    }

    public Integer getQuantidadeMinucias() {
        return quantidadeMinucias;
    }

    public void setQuantidadeMinucias(Integer quantidadeMinucias) {
        this.quantidadeMinucias = quantidadeMinucias;
    }

    public Integer getTamanho() {
        return tamanho;
    }

    public void setTamanho(Integer tamanho) {
        this.tamanho = tamanho;
    }

    /**
     * @return the dataInclusao
     */
    public Date getDataInclusao() {
        return dataInclusao;
    }

    /**
     * @param dataInclusao the dataInclusao to set
     */
    public void setDataInclusao( Date dataInclusao ) {
        this.dataInclusao = dataInclusao;
    }

}
