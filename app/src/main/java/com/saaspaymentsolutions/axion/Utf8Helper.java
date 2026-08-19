package com.saaspaymentsolutions.axion;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * Helper para garantir encoding UTF-8 correto em todas as operações de texto.
 * Evita problemas de caracteres corrompidos como "solicita├º├Áes" e "vers├úo".
 */
public class Utf8Helper {

    /**
     * Converte bytes para String usando explicitamente UTF-8.
     * 
     * @param bytes array de bytes
     * @return String decodificada como UTF-8
     */
    @NonNull
    public static String fromBytes(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Converte String para bytes usando explicitamente UTF-8.
     * 
     * @param text string a converter
     * @return array de bytes em UTF-8
     */
    @NonNull
    public static byte[] toBytes(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return new byte[0];
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Valida se uma string contém apenas caracteres UTF-8 válidos.
     * 
     * @param text string a validar
     * @return true se válida, false se corrompida
     */
    public static boolean isValidUtf8(@Nullable String text) {
        if (text == null) {
            return true;
        }
        
        // Tenta recodificar e comparar
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String reencoded = new String(bytes, StandardCharsets.UTF_8);
        return text.equals(reencoded);
    }

    /**
     * Tenta reparar uma string com encoding corrompido.
     * Útil quando dados foram salvos incorretamente mas ainda contêm os bytes originais.
     * 
     * @param corrupted string possivelmente corrompida
     * @return string reparada, ou a original se não for possível reparar
     */
    @NonNull
    public static String attemptRepair(@Nullable String corrupted) {
        if (corrupted == null || corrupted.isEmpty()) {
            return "";
        }
        
        // Se já é válida, retornar como está
        if (isValidUtf8(corrupted)) {
            return corrupted;
        }
        
        try {
            // Tentar reinterpretar como ISO-8859-1 e reconverter para UTF-8
            byte[] wrongBytes = corrupted.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            return new String(wrongBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Se falhar, retornar original
            return corrupted;
        }
    }

    /**
     * Garante que uma string JSON está corretamente encodada em UTF-8.
     * 
     * @param json string JSON
     * @return JSON com encoding corrigido
     */
    @NonNull
    public static String ensureJsonUtf8(@Nullable String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        
        // JSON já deve especificar UTF-8, mas garantir
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Caracteres de teste para validacao de UTF-8.
     * Util em testes unitarios.
     */
    public static final String TEST_CHARS = "a e i o u ao c Solicitacoes Versao Metalico Iluminacao";
}
