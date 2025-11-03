/*
 *  Copyright (c) 2018 Prodesp Tecnologia da Informação
 * 
 */

package prodesp.bio.migrador.core.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serial;
import java.io.Serializable;

/**
 *
 * @author Wellerson Lopes <welsilva@sp.gov.br>
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties(ignoreUnknown = true)
public class EquipamentoMetadata implements Serializable {

    @Serial
    private static final long serialVersionUID = 7252930211298287131L;
    
    private String marca;
    
    private String modelo;
    
    private String serial;

    public String getMarca() {
        return marca;
    }

    public void setMarca(String marca) {
        this.marca = marca;
    }

    public String getModelo() {
        return modelo;
    }

    public void setModelo(String modelo) {
        this.modelo = modelo;
    }

    public String getSerial() {
        return serial;
    }

    public void setSerial(String serial) {
        this.serial = serial;
    }
    
    
}
