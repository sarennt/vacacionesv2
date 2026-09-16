package com.hrms.vacaciones.service;

import com.hrms.vacaciones.model.TabuladorVacaciones;
import com.hrms.vacaciones.repository.TabuladorVacacionesRepository;
import com.hrms.vacaciones.repository.ConfiguracionSistemaRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TabuladorCache {

    private static List<TabuladorVacaciones> tabuladorMemoria;
    private static int bonoAdministrativoMemoria = 2; // Fallback extremo

    private final TabuladorVacacionesRepository repo;
    private final ConfiguracionSistemaRepository configRepo;

    public TabuladorCache(TabuladorVacacionesRepository repo, ConfiguracionSistemaRepository configRepo) {
        this.repo = repo;
        this.configRepo = configRepo;
    }

    @PostConstruct
    public void init() {
        actualizarCache();
    }

    public void actualizarCache() {
        tabuladorMemoria = repo.findAll(org.springframework.data.domain.Sort.by("anioAntiguedad"));
        bonoAdministrativoMemoria = configRepo.findById("BONO_DIAS_ADMIN")
                .map(c -> {
                    try { return Integer.parseInt(c.getValor()); }
                    catch (Exception e) { return 2; }
                }).orElse(2);
    }

    public static int getDiasLey(int anio) {
        if (tabuladorMemoria == null || tabuladorMemoria.isEmpty()) return 12;

        for (TabuladorVacaciones t : tabuladorMemoria) {
            if (t.getAnioAntiguedad() == anio) {
                return t.getDiasLey();
            }
        }
        return tabuladorMemoria.get(tabuladorMemoria.size() - 1).getDiasLey();
    }

    // ✨ NUEVO: Entregamos el bono sin ir a la BD
    public static int getBonoAdministrativo() {
        return bonoAdministrativoMemoria;
    }
}