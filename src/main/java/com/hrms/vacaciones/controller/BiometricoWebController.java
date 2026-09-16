package com.hrms.vacaciones.controller;

import com.hrms.vacaciones.service.BiometricoImportService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
@RequiredArgsConstructor
public class BiometricoWebController {

    // Inyectamos el motor de importación que armamos hace rato
    private final BiometricoImportService biometricoImportService;

    /**
     * Endpoint que atrapa el archivo .csv del reloj facial y ejecuta la limpieza.
     */
    @PostMapping("/prenomina/importar-txt")
    public String importarRegistrosFaciales(@RequestParam("archivoTxt") MultipartFile file,
                                            HttpSession session,
                                            RedirectAttributes redirectAttributes) {

        // 1. Validar que la sesión esté viva
        Object logueadoObj = session.getAttribute("usuarioLogueado");
        if (logueadoObj == null) {
            return "redirect:/login";
        }

        try {
            // 2. Mandamos el archivo bruto al servicio para su masacre y redondeo
            biometricoImportService.importarRegistrosCSV(file);

            // 3. Si todo sale bien, disparamos la alerta de éxito en la vista
            redirectAttributes.addFlashAttribute("mensajeExito", "¡Boom! Los registros faciales fueron limpiados, redondeados y emparejados con éxito en la base de datos.");
            log.info("Archivo biométrico procesado por el usuario: {}", logueadoObj);

        } catch (Exception e) {
            // Si el CSV viene corrupto o truena algo, avisamos sin tumbar la app
            redirectAttributes.addFlashAttribute("mensajeError", "Error al procesar el archivo del checador: " + e.getMessage());
            log.error("Falla en importación biométrica", e);
        }

        // 4. Recargamos la pantalla maestra de prenómina
        return "redirect:/pantallas/prenomina";
    }
}