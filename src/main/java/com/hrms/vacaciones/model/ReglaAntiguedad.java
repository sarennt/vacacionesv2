package com.hrms.vacaciones.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reglas_antiguedad")
public class ReglaAntiguedad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tipo_contrato_id")
    private TipoContrato tipoContrato;

    @Column(name = "anios_antiguedad")
    private Integer aniosAntiguedad;

    @Column(name = "dias_otorgados", precision = 5, scale = 2)
    private BigDecimal diasOtorgados;
}
