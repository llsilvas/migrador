/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package prodesp.bio.migrador.core.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 *
 * @author roger
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProblemaMetadata implements Serializable {
    private String codigo;
    private String descricao;

    public ProblemaMetadata() {
    }
    
    public ProblemaMetadata( String c, String d ) {
        codigo = c;
        descricao = d;
    }
    
    @Override
    public String toString() {
        return "[cod: " + codigo + ", desc: " + descricao + "]";
    }
    /**
     * @return the codigo
     */
    public String getCodigo() {
        return codigo;
    }

    /**
     * @param codigo the codigo to set
     */
    public void setCodigo( String codigo ) {
        this.codigo = codigo;
    }

    /**
     * @return the descricao
     */
    public String getDescricao() {
        return descricao;
    }

    /**
     * @param descricao the descricao to set
     */
    public void setDescricao( String descricao ) {
        this.descricao = descricao;
    }
    
}
