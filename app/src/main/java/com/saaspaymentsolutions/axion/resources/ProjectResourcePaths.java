package com.saaspaymentsolutions.axion.resources;

import com.saaspaymentsolutions.axion.ProjectManager;

import java.io.File;

/** Resolves resource folders according to the currently opened project kind. */
public final class ProjectResourcePaths {
    private static final String WEB_ASSETS = "assets";
    private static final String WEB_IMAGES = WEB_ASSETS + "/images";
    private static final String WEB_SOUNDS = WEB_ASSETS + "/sounds";
    private static final String WEB_FONTS = WEB_ASSETS + "/fonts";

    private ProjectResourcePaths() {
    }

    public static boolean isWebProject(String projectId) {
        return ProjectManager.PROJECT_KIND_WEB.equals(ProjectManager.getProjectKind(projectId));
    }

    public static File getCategoryDirectory(String projectId, String resourceType) {
        File root = new File(ProjectManager.getProjectDir(projectId));
        if (isWebProject(projectId)) {
            return new File(root, getWebCategoryRelativePath(resourceType));
        }
        if (ProjectResourceManagerActivity.TYPE_IMAGE.equals(resourceType)) {
            return new File(root, "app/src/main/res/drawable");
        }
        if (ProjectResourceManagerActivity.TYPE_SOUND.equals(resourceType)) {
            return new File(root, "app/src/main/res/raw");
        }
        return new File(root, "app/src/main/res/font");
    }

    public static File getAssetManagerRoot(String projectId) {
        File root = new File(ProjectManager.getProjectDir(projectId));
        return isWebProject(projectId)
                ? new File(root, WEB_ASSETS)
                : new File(root, "app/src/main/res");
    }

    public static String getRelativeCategoryLabel(String projectId, String resourceType) {
        if (isWebProject(projectId)) {
            return getWebCategoryRelativePath(resourceType);
        }
        if (ProjectResourceManagerActivity.TYPE_IMAGE.equals(resourceType)) return "app/src/main/res/drawable";
        if (ProjectResourceManagerActivity.TYPE_SOUND.equals(resourceType)) return "app/src/main/res/raw";
        return "app/src/main/res/font";
    }

    public static String getRelativeAssetRootLabel(String projectId) {
        return isWebProject(projectId) ? WEB_ASSETS : "app/src/main/res";
    }

    public static void ensureWebFolders(String projectId) {
        if (!isWebProject(projectId)) return;
        File root = new File(ProjectManager.getProjectDir(projectId));
        new File(root, WEB_IMAGES).mkdirs();
        new File(root, WEB_SOUNDS).mkdirs();
        new File(root, WEB_FONTS).mkdirs();
    }

    private static String getWebCategoryRelativePath(String resourceType) {
        if (ProjectResourceManagerActivity.TYPE_IMAGE.equals(resourceType)) return WEB_IMAGES;
        if (ProjectResourceManagerActivity.TYPE_SOUND.equals(resourceType)) return WEB_SOUNDS;
        return WEB_FONTS;
    }
}
