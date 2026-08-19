package com.saaspaymentsolutions.axion;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import com.saaspaymentsolutions.axion.FileUtil;

public class ProjectManager {
    public static final String PROJECT_KIND_KEY = "project_kind";
    public static final String PROJECT_KIND_ANDROID_STUDIO = "android-studio";
    public static final String PROJECT_KIND_WEB = "web";

    private static final String AS_PROJECTS_DIR_NAME = ".axion_ide";
    private static final String WEB_PROJECTS_DIR_NAME = ".axion_ide_web";

    public static ArrayList<HashMap<String, Object>> a() {
        ArrayList<HashMap<String, Object>> arrayList = new ArrayList<>();
        Set<String> knownProjectIds = new HashSet<>();
        addProjectsFromRoot(arrayList, new File(getAndroidStudioProjectsRoot()), knownProjectIds, PROJECT_KIND_ANDROID_STUDIO);
        addProjectsFromRoot(arrayList, new File(getWebProjectsRoot()), knownProjectIds, PROJECT_KIND_WEB);
        return arrayList;
    }

    private static void addProjectsFromRoot(ArrayList<HashMap<String, Object>> projects, File root,
                                            Set<String> knownProjectIds, String projectKind) {
        if (!root.exists()) {
            root.mkdirs();
        }
        File[] listFiles = root.listFiles();
        if (listFiles == null) {
            return;
        }
        for (File file : listFiles) {
            try {
                if (!file.isDirectory()) continue;
                if (knownProjectIds.contains(file.getName())) continue;

                File projectMetadata = new File(file, "project");
                if (projectMetadata.exists()) {
                    String json = FileUtil.readFile(projectMetadata.getAbsolutePath());
                    if (json == null || json.isEmpty()) continue;

                    JSONObject obj = new JSONObject(json);
                    HashMap<String, Object> metadata = new HashMap<>();
                    java.util.Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = obj.get(key);
                        if (value instanceof JSONObject) {
                            metadata.put(key, value.toString());
                        } else if (value instanceof JSONArray) {
                            metadata.put(key, value.toString());
                        } else {
                            metadata.put(key, value);
                        }
                    }

                    String scId = String.valueOf(metadata.get("sc_id"));
                    if (scId.equals(file.getName())) {
                        if (metadata.get(PROJECT_KIND_KEY) == null || String.valueOf(metadata.get(PROJECT_KIND_KEY)).isEmpty()) {
                            metadata.put(PROJECT_KIND_KEY, projectKind);
                        }
                        metadata.put("proj_type", 2);
                        metadata.put("studio_path", file.getAbsolutePath());
                        projects.add(metadata);
                        knownProjectIds.add(file.getName());
                    }
                }
            } catch (Throwable e) {
                Log.e("ProjectManager", "Error reading project: " + file.getName(), e);
            }
        }
    }

    public static HashMap<String, Object> a(String str) {
        for (HashMap<String, Object> project : a()) {
            if (MapUtils.c(project, "my_sc_pkg_name").equals(str) && MapUtils.b(project, "proj_type") == 1) {
                return project;
            }
        }
        return null;
    }

    public static HashMap<String, Object> b(String scId) {
        File webDir = new File(getWebProjectsRoot(), scId);
        if (webDir.exists()) {
            return readProjectMetadata(webDir, scId, PROJECT_KIND_WEB);
        }
        return readProjectMetadata(new File(getAndroidStudioProjectsDir(), scId), scId, PROJECT_KIND_ANDROID_STUDIO);
    }

    /**
     * Resolve a pasta física de um projeto existente, procurando primeiro em
     * {@code .axion_ide_web} (projetos web) e depois em {@code .axion_ide}
     * (projetos nativos Android Studio).
     */
    private static File resolveExistingProjectDir(String scId) {
        File webDir = new File(getWebProjectsRoot(), scId);
        if (webDir.exists()) return webDir;
        return new File(getAndroidStudioProjectsDir(), scId);
    }

    private static HashMap<String, Object> readProjectMetadata(File projectDirectory, String expectedId, String projectKind) {
        try {
            if (!projectDirectory.exists()) return null;

            String path = projectDirectory.getAbsolutePath() + File.separator + "project";
            File metadataFile = new File(path);
            if (!metadataFile.exists()) return null;

            String json = FileUtil.readFile(path);
            if (json == null || json.isEmpty()) return null;

            JSONObject obj = new JSONObject(json);
            HashMap<String, Object> metadata = new HashMap<>();
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = obj.get(key);
                if (value instanceof JSONObject) {
                    metadata.put(key, value.toString());
                } else if (value instanceof JSONArray) {
                    metadata.put(key, value.toString());
                } else {
                    metadata.put(key, value);
                }
            }

            if (!String.valueOf(metadata.get("sc_id")).equals(expectedId)) return null;

            if (metadata.get(PROJECT_KIND_KEY) == null || String.valueOf(metadata.get(PROJECT_KIND_KEY)).isEmpty()) {
                metadata.put(PROJECT_KIND_KEY, projectKind);
            }
            metadata.put("proj_type", 2);
            metadata.put("studio_path", projectDirectory.getAbsolutePath());
            return metadata;
        } catch (Exception e) {
            Log.e("ProjectManager", "Error reading project metadata", e);
            return null;
        }
    }

    public static void saveAndroidStudioProject(String scId, HashMap<String, Object> data) {
        data.put(PROJECT_KIND_KEY, PROJECT_KIND_ANDROID_STUDIO);
        data.put("proj_type", 2);
        data.put("studio_path", getAndroidStudioProjectPath(scId));
        saveProjectMetadata(new File(getAndroidStudioProjectsDir(), scId), data);
    }

    public static void saveWebProject(String scId, HashMap<String, Object> data) {
        data.put(PROJECT_KIND_KEY, PROJECT_KIND_WEB);
        data.put("proj_type", 2);
        data.put("studio_path", getWebProjectPath(scId));
        saveProjectMetadata(new File(getWebProjectsDir(), scId), data);
    }

    private static void saveProjectMetadata(File projectDirectory, HashMap<String, Object> data) {
        if (!projectDirectory.exists()) {
            projectDirectory.mkdirs();
        }
        try {
            JSONObject obj = new JSONObject();
            for (HashMap.Entry<String, Object> entry : data.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
            String path = projectDirectory.getAbsolutePath() + File.separator + "project";
            FileUtil.writeFile(path, obj.toString());
        } catch (Exception e) {
            Log.e("ProjectManager", "Error saving project metadata", e);
        }
    }

    public static void b(String scId, HashMap<String, Object> data) {
        File projectDir = resolveExistingProjectDir(scId);
        if (projectDir.exists()) {
            String path = projectDir.getAbsolutePath() + File.separator + "project";
            try {
                String json = FileUtil.readFile(path);
                if (json == null || json.isEmpty()) return;

                JSONObject existing = new JSONObject(json);
                if (!String.valueOf(existing.get("sc_id")).equals(scId)) return;

                if (data.containsKey("isIconAdaptive")) existing.put("isIconAdaptive", data.get("isIconAdaptive"));
                if (data.containsKey("custom_icon")) existing.put("custom_icon", data.get("custom_icon"));
                existing.put("my_sc_pkg_name", data.get("my_sc_pkg_name"));
                existing.put("my_ws_name", data.get("my_ws_name"));
                existing.put("my_app_name", data.get("my_app_name"));
                existing.put("sc_ver_code", data.get("sc_ver_code"));
                existing.put("sc_ver_name", data.get("sc_ver_name"));
                existing.put("sketchware_ver", data.get("sketchware_ver"));
                existing.put("color_accent", data.get("color_accent"));
                existing.put("color_primary", data.get("color_primary"));
                existing.put("color_primary_dark", data.get("color_primary_dark"));
                existing.put("color_control_highlight", data.get("color_control_highlight"));
                existing.put("color_control_normal", data.get("color_control_normal"));
                if (data.containsKey(PROJECT_KIND_KEY)) existing.put(PROJECT_KIND_KEY, data.get(PROJECT_KIND_KEY));
                if (data.containsKey("proj_type")) existing.put("proj_type", data.get("proj_type"));
                if (data.containsKey("studio_path")) existing.put("studio_path", data.get("studio_path"));

                FileUtil.writeFile(path, existing.toString());
            } catch (Exception e) {
                Log.e("ProjectManager", "Error updating project metadata", e);
            }
        }
    }

    public static boolean isWebProject(HashMap<String, Object> projectMap) {
        return PROJECT_KIND_WEB.equals(MapUtils.c(projectMap, PROJECT_KIND_KEY));
    }

    public static boolean isAndroidStudioProject(HashMap<String, Object> projectMap) {
        if (isWebProject(projectMap)) return false;
        return PROJECT_KIND_ANDROID_STUDIO.equals(MapUtils.c(projectMap, PROJECT_KIND_KEY)) || MapUtils.b(projectMap, "proj_type") == 2;
    }

    /** Retorna o tipo do projeto ({@link #PROJECT_KIND_WEB} ou {@link #PROJECT_KIND_ANDROID_STUDIO}) a partir do scId. */
    public static String getProjectKind(String scId) {
        if (new File(getWebProjectsRoot(), scId).exists()) return PROJECT_KIND_WEB;
        return PROJECT_KIND_ANDROID_STUDIO;
    }

    public static void e(String scId) {
        deleteAndroidStudioProject(null, scId);
    }

    /**
     * Apaga apenas a pasta física do projeto (não apaga histórico de chat).
     * Preferencialmente, use {@link #deleteAndroidStudioProject(Context, String)}
     * para também limpar mensagens/threads/diffs/etc vinculados ao projeto.
     */
    public static void deleteAndroidStudioProject(String scId) {
        deleteAndroidStudioProject(null, scId);
    }

    /**
     * Exclui um projeto Android Studio e TODO o seu histórico persistido:
     * mensagens de chat, threads, diffs, referências de arquivos, resumos e planos.
     *
     * @param context Contexto Android (usado para ChatHistoryManager; pode ser null
     *                caso não esteja disponível — neste caso a pasta física é apagada
     *                mas o histórico não).
     * @param scId    ID do projeto a ser excluído.
     */
    public static void deleteAndroidStudioProject(Context context, String scId) {
        // Clear persisted diff state before removing the project root.
        FileChangeTracker.clearChanges(scId);
        // 1. Apagar diretório físico do projeto em /sdcard/.axion_ide/<scId>/
        //    ou /sdcard/.axion_ide_web/<scId>/ (projetos web).
        File asProject = resolveExistingProjectDir(scId);
        if (asProject.exists()) {
            deleteRecursive(asProject);
            Log.d("ProjectManager", "Projeto apagado: " + asProject.getAbsolutePath());
        }

        // 2. Apagar todo o histórico de chat, threads, mensagens, plans, diffs vinculados
        if (context != null && scId != null) {
            ChatHistoryManager historyManager = null;
            try {
                historyManager = new ChatHistoryManager(context);
                historyManager.deleteProjectHistory(scId);
                Log.d("ProjectManager", "Histórico de chat apagado para scId=" + scId);
            } catch (Exception ex) {
                Log.e("ProjectManager", "Falha ao apagar histórico do projeto " + scId, ex);
            } finally {
                if (historyManager != null) historyManager.shutdown();
            }
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    public static String b() {
        int nextId = 601;
        Set<String> existingIds = new HashSet<>();
        collectProjectIds(existingIds, new File(getAndroidStudioProjectsRoot()));
        collectProjectIds(existingIds, new File(getWebProjectsRoot()));
        for (String id : existingIds) {
            try {
                nextId = Math.max(nextId, Integer.parseInt(id) + 1);
            } catch (Exception ignored) {
            }
        }
        while (existingIds.contains(String.valueOf(nextId))) {
            nextId++;
        }
        return String.valueOf(nextId);
    }

    private static void collectProjectIds(Set<String> ids, File root) {
        if (!root.exists()) return;
        File[] listFiles = root.listFiles();
        if (listFiles == null) return;
        for (File file : listFiles) {
            if (file.exists() && file.isDirectory()) {
                ids.add(file.getName());
            }
        }
    }

    public static String c() {
        ArrayList<HashMap<String, Object>> projects = a();
        ArrayList<Integer> projectIndices = new ArrayList<>();

        for (HashMap<String, Object> project : projects) {
            String workspaceName = MapUtils.c(project, "my_ws_name");
            if (workspaceName.equals("NewProject")) {
                projectIndices.add(1);
            } else if (workspaceName.indexOf("NewProject") == 0) {
                try {
                    projectIndices.add(Integer.parseInt(workspaceName.substring(10)));
                } catch (Exception ignored) {
                }
            }
        }

        projectIndices.sort(Comparator.naturalOrder());
        int lastMatch = 0;
        for (int index : projectIndices) {
            if (index == lastMatch + 1) {
                lastMatch = index;
            } else if (index != lastMatch) {
                break;
            }
        }

        return lastMatch == 0 ? "NewProject" : "NewProject" + (lastMatch + 1);
    }

    public static String getAndroidStudioProjectsRoot() {
        return Environment.getExternalStorageDirectory().getAbsolutePath()
                + File.separator + AS_PROJECTS_DIR_NAME;
    }

    public static String getAndroidStudioProjectsDir() {
        File dir = new File(getAndroidStudioProjectsRoot());
        if (!dir.exists()) dir.mkdirs();
        return dir.getAbsolutePath();
    }

    public static String getAndroidStudioProjectPath(String scId) {
        // Projetos web vivem em .axion_ide_web; resolvê-los aqui garante que TODA a
        // resolução de caminho por scId (chat, ferramentas, ícones) aponte para a
        // pasta certa sem alterar cada chamador.
        File webDir = new File(getWebProjectsRoot(), scId);
        if (webDir.exists()) return webDir.getAbsolutePath();
        File projectDir = new File(getAndroidStudioProjectsDir(), scId);
        if (!projectDir.exists()) projectDir.mkdirs();
        return projectDir.getAbsolutePath();
    }

    public static String getProjectDir(String scId) {
        return getAndroidStudioProjectPath(scId);
    }

    // ------------------------------------------------------------------
    // Projetos web (jogos Three.js) — pasta .axion_ide_web
    // ------------------------------------------------------------------

    public static String getWebProjectsRoot() {
        return Environment.getExternalStorageDirectory().getAbsolutePath()
                + File.separator + WEB_PROJECTS_DIR_NAME;
    }

    public static String getWebProjectsDir() {
        File dir = new File(getWebProjectsRoot());
        if (!dir.exists()) dir.mkdirs();
        return dir.getAbsolutePath();
    }

    public static String getWebProjectPath(String scId) {
        File projectDir = new File(getWebProjectsDir(), scId);
        if (!projectDir.exists()) projectDir.mkdirs();
        return projectDir.getAbsolutePath();
    }

    /**
     * Cria um novo projeto web (jogo Three.js) em {@code .axion_ide_web/<id>/},
     * já com o scaffold modular (bootstrap, core, entidades e sistemas) e metadados.
     *
     * @return o scId do projeto criado.
     */
    public static String createWebProject(String appName) {
        return createWebProject(appName, null);
    }

    /**
     * Cria um novo projeto web com Context para extrair o template do ZIP.
     *
     * @param appName nome do aplicativo
     * @param context contexto Android para acessar assets (pode ser null)
     * @return o scId do projeto criado.
     */
    public static String createWebProject(String appName, Context context) {
        String scId = b();
        String dir = getWebProjectPath(scId);
        writeWebTemplate(dir, appName, context);
        new File(dir, "assets/images").mkdirs();
        new File(dir, "assets/sounds").mkdirs();
        new File(dir, "assets/fonts").mkdirs();

        String wsName = (appName == null || appName.trim().isEmpty()) ? c() : appName.trim();
        String pkg = "web.game." + wsName.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (pkg.equals("web.game.")) pkg = "web.game.app";

        HashMap<String, Object> data = new HashMap<>();
        data.put("sc_id", scId);
        data.put("my_ws_name", wsName);
        data.put("my_app_name", wsName);
        data.put("my_sc_pkg_name", pkg);
        data.put("my_sc_reg_dt", new DateUtils().a("yyyyMMddHHmmss"));
        data.put("sc_ver_code", "1");
        data.put("sc_ver_name", "1.0");
        data.put("sketchware_ver", 61);
        data.put("custom_icon", false);
        saveWebProject(scId, data);
        return scId;
    }

    private static void writeWebTemplate(String dir, String appName, Context context) {
        // Tentar usar o novo template Three.js do ZIP
        boolean templateExtracted = false;
        if (context != null) {
            try {
                try (InputStream inputStream = context.getAssets().open("theejs_template/theejs.zip");
                     java.util.zip.ZipInputStream zipInputStream = new java.util.zip.ZipInputStream(inputStream)) {
                    FileUtil.extractZipTo(zipInputStream, dir);
                    templateExtracted = true;
                }
            } catch (Exception e) {
                Log.w("ProjectManager", "Falha ao extrair template theejs.zip, usando fallback", e);
            }
        }
        
        // Fallback para o template antigo se não conseguiu extrair o ZIP
        if (!templateExtracted) {
            String title = (appName == null || appName.trim().isEmpty()) ? "Axion Web Game" : appName.trim();
            new File(dir, "js" + File.separator + "core").mkdirs();
            new File(dir, "js" + File.separator + "entities").mkdirs();
            new File(dir, "js" + File.separator + "systems").mkdirs();
            FileUtil.writeFile(dir + File.separator + "index.html", WEB_INDEX_HTML.replace("$title$", title));
            FileUtil.writeFile(dir + File.separator + "js" + File.separator + "main.js", WEB_MAIN_JS);
            FileUtil.writeFile(dir + File.separator + "js" + File.separator + "config.js", WEB_CONFIG_JS);
            FileUtil.writeFile(dir + File.separator + "js" + File.separator + "core" + File.separator + "createThreeApp.js", WEB_APP_JS);
            FileUtil.writeFile(dir + File.separator + "js" + File.separator + "core" + File.separator + "createScene.js", WEB_SCENE_JS);
            FileUtil.writeFile(dir + File.separator + "js" + File.separator + "entities" + File.separator + "createCube.js", WEB_CUBE_JS);
            FileUtil.writeFile(dir + File.separator + "js" + File.separator + "systems" + File.separator + "animationSystem.js", WEB_ANIMATION_JS);
            FileUtil.writeFile(dir + File.separator + "js" + File.separator + "systems" + File.separator + "resizeSystem.js", WEB_RESIZE_JS);
            FileUtil.writeFile(dir + File.separator + "css" + File.separator + "style.css", WEB_STYLE_CSS);
            FileUtil.writeFile(dir + File.separator + "leia_me.md", WEB_README);
        }
    }

    private static final String WEB_INDEX_HTML =
            "<!DOCTYPE html>\n"
            + "<html lang=\"pt-br\">\n"
            + "<head>\n"
            + "  <meta charset=\"UTF-8\" />\n"
            + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\" />\n"
            + "  <title>$title$</title>\n"
            + "  <link rel=\"stylesheet\" href=\"css/style.css\" />\n"
            + "  <script type=\"importmap\">\n"
            + "  {\n"
            + "    \"imports\": {\n"
            + "      \"three\": \"https://cdn.jsdelivr.net/npm/three@0.180.0/build/three.module.js\"\n"
            + "    }\n"
            + "  }\n"
            + "  </script>\n"
            + "</head>\n"
            + "<body>\n"
            + "  <div id=\"info\"><h1>Three.js Modular</h1><p>Cena, entidades e sistemas separados.</p></div>\n"
            + "  <script type=\"module\" src=\"js/main.js\"></script>\n"
            + "</body>\n"
            + "</html>\n";

    private static final String WEB_MAIN_JS =
            "import { createThreeApp } from './core/createThreeApp.js';\n\n"
            + "const app = createThreeApp(document.body);\n"
            + "app.start();\n\n"
            + "window.addEventListener('pagehide', app.dispose, { once: true });\n";

    private static final String WEB_CONFIG_JS =
            "export const SCENE_CONFIG = Object.freeze({\n"
            + "  backgroundColor: 0x1a1a1a,\n"
            + "  objectColor: 0x61dafb,\n"
            + "  cameraFov: 75, cameraNear: 0.1, cameraFar: 1000, cameraZ: 5,\n"
            + "  maxPixelRatio: 2, rotationSpeed: 0.01,\n"
            + "});\n";

    private static final String WEB_APP_JS =
            "import { createScene } from './createScene.js';\n"
            + "import { createCube } from '../entities/createCube.js';\n"
            + "import { createAnimationSystem } from '../systems/animationSystem.js';\n"
            + "import { createResizeSystem } from '../systems/resizeSystem.js';\n\n"
            + "export function createThreeApp(container) {\n"
            + "  const context = createScene(container);\n"
            + "  const cube = createCube();\n"
            + "  context.scene.add(cube);\n"
            + "  const resizeSystem = createResizeSystem(context.camera, context.renderer);\n"
            + "  const animationSystem = createAnimationSystem(context, cube);\n"
            + "  return {\n"
            + "    start() { resizeSystem.start(); animationSystem.start(); },\n"
            + "    dispose() { animationSystem.stop(); resizeSystem.stop(); cube.geometry.dispose(); cube.material.dispose(); context.renderer.dispose(); context.renderer.domElement.remove(); },\n"
            + "  };\n"
            + "}\n";

    private static final String WEB_SCENE_JS =
            "import * as THREE from 'three';\n"
            + "import { SCENE_CONFIG } from '../config.js';\n\n"
            + "export function createScene(container) {\n"
            + "  const scene = new THREE.Scene();\n"
            + "  scene.background = new THREE.Color(SCENE_CONFIG.backgroundColor);\n"
            + "  const camera = new THREE.PerspectiveCamera(SCENE_CONFIG.cameraFov, window.innerWidth / window.innerHeight, SCENE_CONFIG.cameraNear, SCENE_CONFIG.cameraFar);\n"
            + "  camera.position.z = SCENE_CONFIG.cameraZ;\n"
            + "  const renderer = new THREE.WebGLRenderer({ antialias: true });\n"
            + "  renderer.setPixelRatio(Math.min(window.devicePixelRatio, SCENE_CONFIG.maxPixelRatio));\n"
            + "  renderer.setSize(window.innerWidth, window.innerHeight);\n"
            + "  container.appendChild(renderer.domElement);\n"
            + "  return { scene, camera, renderer };\n"
            + "}\n";

    private static final String WEB_CUBE_JS =
            "import * as THREE from 'three';\n"
            + "import { SCENE_CONFIG } from '../config.js';\n\n"
            + "export function createCube() {\n"
            + "  const geometry = new THREE.BoxGeometry(2, 2, 2);\n"
            + "  const material = new THREE.MeshBasicMaterial({ color: SCENE_CONFIG.objectColor });\n"
            + "  return new THREE.Mesh(geometry, material);\n"
            + "}\n";

    private static final String WEB_ANIMATION_JS =
            "import { SCENE_CONFIG } from '../config.js';\n\n"
            + "export function createAnimationSystem({ scene, camera, renderer }, cube) {\n"
            + "  let frameId = null;\n"
            + "  function renderFrame() {\n"
            + "    cube.rotation.x += SCENE_CONFIG.rotationSpeed;\n"
            + "    cube.rotation.y += SCENE_CONFIG.rotationSpeed;\n"
            + "    renderer.render(scene, camera);\n"
            + "    frameId = window.requestAnimationFrame(renderFrame);\n"
            + "  }\n"
            + "  return { start() { if (frameId === null) renderFrame(); }, stop() { if (frameId !== null) { window.cancelAnimationFrame(frameId); frameId = null; } } };\n"
            + "}\n";

    private static final String WEB_RESIZE_JS =
            "export function createResizeSystem(camera, renderer) {\n"
            + "  function resize() {\n"
            + "    camera.aspect = window.innerWidth / window.innerHeight;\n"
            + "    camera.updateProjectionMatrix();\n"
            + "    renderer.setSize(window.innerWidth, window.innerHeight);\n"
            + "  }\n"
            + "  return { start() { window.addEventListener('resize', resize); resize(); }, stop() { window.removeEventListener('resize', resize); } };\n"
            + "}\n";

    private static final String WEB_STYLE_CSS =
            "* { margin: 0; padding: 0; box-sizing: border-box; }\n"
            + "html, body { width: 100%; height: 100%; overflow: hidden; background: #0e0e14; }\n"
            + "canvas { display: block; width: 100vw; height: 100vh; touch-action: none; }\n"
            + "#info { position: fixed; z-index: 1; top: 16px; left: 16px; color: white; font: 14px sans-serif; }\n";

    private static final String WEB_README =
            "# Projeto Three.js modular\n\n"
            + "`js/main.js` apenas inicializa a aplicação. Cena e infraestrutura ficam em `js/core`, "
            + "objetos em `js/entities`, comportamentos contínuos em `js/systems` e constantes em `js/config.js`.\n\n"
            + "Mantenha uma responsabilidade por módulo e preserve o import map do Three.js r180 em `index.html`.\n";
}




