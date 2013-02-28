package at.bestsolution.releng.distrobuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarInputStream;
import org.apache.tools.tar.TarOutputStream;

/**
 * Java class that abstracts/encapsulates building Eclipse distributions to
 * allow them to be automated with Java code.
 */
public class DistroBuilder {

    // http://help.eclipse.org/juno/index.jsp?topic=/org.eclipse.platform.doc.isv/guide/p2_director.html

    private static final FileFilter DIRS = new FileFilter() {
        public boolean accept(File pathname) {
            return pathname.isDirectory();
        }
    };

    private String targetDirectory;
    private String p2DirectorExecutable;
    private String staticReposDirectory;
    private String buildDirectory;
    private String distDirectory;
    private String profile;
    private String appDefinition;

    private List<InstallUnit> iuList = new ArrayList<InstallUnit>();
    private List<UpdateSite> siteList = new ArrayList<UpdateSite>();
    private List<P2Repository> repoList = new ArrayList<P2Repository>();

    private static final int OWNER_EXEC = 00100;
    private static final int GROUP_EXEC = 00010;
    private static final int OTHER_EXEC = 00001;

    static class PipeThread extends Thread {
        private final InputStream in;
        private final PrintStream out;

        public PipeThread(InputStream in, PrintStream out) {
            setDaemon(true);
            this.in = in;
            this.out = out;
        }

        @Override
        public void run() {
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            String l;
            try {
                while ((l = r.readLine()) != null) {
                    out.println(l);
                }
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private void buildDistro(File targetSdksDir, String version, String os, String arch) throws DistroBuildException {

        System.out.println("Build distro for " + version + " - " + os + " - " + arch);

        List<String> iuList = filterList(this.iuList, version, os, arch);
        List<String> repos = filterList(this.siteList, version, os, arch);
        try {
            repos.addAll(makeLocalRepos(new File(buildDirectory, "cache"), filterList(repoList, version, os, arch)));
        } catch (IOException e) {
            throw new DistroBuildException("Error downloading repositories", e);
        }

        collectZipFiles(repos, staticReposDirectory, "shared", os, arch);
        collectZipFiles(repos, staticReposDirectory, version, os, arch);

        if (p2DirectorExecutable == null) {
            throw new DistroBuildException("P2 director executable not defined");
        }
        if (!new File(p2DirectorExecutable).exists()) {
            throw new DistroBuildException("P2 director executable not found at: " + p2DirectorExecutable);
        }

        List<String> command = new LinkedList<String>();
        command.add(p2DirectorExecutable);
        command.add("-nosplash");
        command.add("-application");
        command.add("org.eclipse.equinox.p2.director");
        command.add("-consoleLog");
        command.add("-profileProperties");
        command.add("org.eclipse.update.install.features=true");
        command.add("-profile");
        command.add(profile == null ? "SDKProfile" : profile);
        command.add("-installIU");
        command.add(join(iuList, ","));
        command.add("-repository");
        command.add(join(repos, ","));

        for (File targetSdk : targetSdksDir.listFiles()) {
            if (targetSdk.isFile()) {
                File f = new File(buildDirectory, "tmp");
                if (f.exists()) {
                    deleteDirectory(f);
                }

                File rootDir;
                try {
                    rootDir = uncompress(targetSdk, f);
                } catch (IOException e1) {
                    throw new DistroBuildException("Failed to extract target SDK: " + targetSdk.getAbsolutePath());
                }

                ProcessBuilder builder = new ProcessBuilder();
                builder.command().addAll(command);

                builder.command().add("-destination");
                builder.command().add(rootDir.getAbsolutePath());

                try {
                    Process p = builder.start();
                    PipeThread stdThread = new PipeThread(p.getInputStream(), System.out);
                    stdThread.start();
                    PipeThread errThread = new PipeThread(p.getErrorStream(), System.err);
                    errThread.start();
                    if (p.waitFor() == 0) {
                        File distDir = new File(distDirectory);
                        distDir.mkdirs();
                        File out = new File(distDir, constructFilename(targetSdk.getName(), appDefinition));
                        compress(rootDir, out);
                    } else {
                        System.err.println("Export failed");
                    }
                    stdThread.join();
                    errThread.join();
                } catch (InterruptedException e) {
                    throw new DistroBuildException("Interrupted while waiting for program to finish", e);
                } catch (IOException e) {
                    throw new DistroBuildException(e);
                }

            }
        }
    }

    private static void compress(File sourceDir, File targetFile) throws IOException {
        List<String> fileList = new ArrayList<String>();
        collectFiles(fileList, sourceDir, "");

        if (targetFile.getName().endsWith(".zip")) {
            targetFile.getParentFile().mkdirs();
            ZipOutputStream out = new ZipOutputStream(new FileOutputStream(targetFile));

            for (String f : fileList) {
                ZipEntry e = new ZipEntry(sourceDir.getName() + "/" + f);
                out.putNextEntry(e);

                FileInputStream in = new FileInputStream(new File(sourceDir, f));
                byte[] buf = new byte[1024];
                int l;

                while ((l = in.read(buf)) != -1) {
                    out.write(buf, 0, l);
                }
                in.close();
                out.closeEntry();
            }

            out.close();
        } else {
            targetFile.getParentFile().mkdirs();
            TarOutputStream out = new TarOutputStream(new GZIPOutputStream(new FileOutputStream(targetFile)));
            out.setLongFileMode(TarOutputStream.LONGFILE_GNU);

            for (String f : fileList) {
                TarEntry e = new TarEntry(sourceDir.getName() + "/" + f);
                File tarFile = new File(sourceDir, f);
                if (tarFile.canExecute()) {
                    e.setMode(0755);
                }
                e.setSize(tarFile.length());
                out.putNextEntry(e);

                FileInputStream in = new FileInputStream(tarFile);
                byte[] buf = new byte[1024];
                int l;

                while ((l = in.read(buf)) != -1) {
                    out.write(buf, 0, l);
                }
                in.close();
                out.closeEntry();
            }

            out.close();
        }
    }

    private static void collectFiles(List<String> files, File dir, String prefix) {
        for (String f : dir.list()) {
            File fd = new File(dir, f);
            if (fd.isDirectory()) {
                collectFiles(files, fd, prefix.isEmpty() ? f : prefix + "/" + f);
            } else {
                files.add(prefix.isEmpty() ? f : prefix + "/" + f);
            }
        }
    }

    private static String constructFilename(String sourceName, String appDefinition) {
        String suffix;
        if (sourceName.endsWith(".zip")) {
            suffix = ".zip";
        } else {
            suffix = ".tar.gz";
        }
        if (appDefinition == null) {
            appDefinition = "distro";
        }
        return sourceName.substring(0, sourceName.length() - suffix.length()) + "-" + appDefinition + suffix;
    }

    private static List<String> makeLocalRepos(File cacheDirectory, List<String> repositories) throws IOException {
        List<String> rv = new ArrayList<String>();
        for (String repo : repositories) {
            if (repo.startsWith("http://")) {
                repo = downloadFile(new URL(repo), cacheDirectory).getAbsolutePath();
            }

            rv.add(toZipString(new File(repo)));
        }

        return rv;
    }

    private static File downloadFile(URL url, File cacheDirectory) throws IOException {
        boolean download = true;
        MessageDigest d;
        try {
            d = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Unable to verify downloaded file", e);
        }
        d.update(url.toString().getBytes());
        String fileName = new BigInteger(1, d.digest()).toString(16) + ".zip";
        cacheDirectory.mkdirs();
        File f = new File(cacheDirectory, fileName);

        if (f.exists()) {
            HttpURLConnection.setFollowRedirects(false);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("HEAD");
            if (con.getResponseCode() == HttpURLConnection.HTTP_OK) {
                long lastmodified = con.getLastModified();
                download = f.lastModified() < lastmodified;
            }
        }

        if (download) {
            slurp(f, url);
        }
        return f;
    }

    private static boolean slurp(File targetFile, URL url) throws IOException {
        targetFile.delete();
        File f = new File(targetFile.getAbsolutePath() + ".part");
        FileOutputStream out = new FileOutputStream(f);

        InputStream in = url.openStream();
        byte[] buf = new byte[1024];
        int l;
        while ((l = in.read(buf)) != -1) {
            out.write(buf, 0, l);
        }
        out.close();
        return f.renameTo(targetFile);
    }

    private static List<String> filterList(List<? extends FilteredElement> list, String version, String os, String arch) {
        List<String> rv = new ArrayList<String>();

        for (FilteredElement u : list) {
            if (u.getVersion() != null && !u.getVersion().equals(version)) {
                continue;
            }

            if (u.getOs() != null && !u.getOs().equals(os)) {
                continue;
            }

            if (u.getArch() != null && !u.getArch().equals(arch)) {
                continue;
            }

            rv.add(u.getValue());
        }

        return rv;
    }

    private static File uncompress(File compressedFile, File targetDirectory) throws IOException {
        File targetDir = null;
        if (compressedFile.getName().endsWith(".tar.gz")) {

            TarInputStream in = new TarInputStream(new GZIPInputStream(new FileInputStream(compressedFile)));
            TarEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    File f = new File(targetDirectory, e.getName());
                    f.mkdirs();
                    if (targetDir == null) {
                        targetDir = f;
                    }
                } else {
                    File f = new File(targetDirectory, e.getName());
                    in.copyEntryContents(new FileOutputStream(f));

                    int m = e.getMode();
                    if ((m & OWNER_EXEC) == OWNER_EXEC || (m & GROUP_EXEC) == GROUP_EXEC
                            || (m & OTHER_EXEC) == OTHER_EXEC) {
                        f.setExecutable(true, false);
                    } else if (e.getLinkName() != null && e.getLinkName().trim().length() > 0) {
                        throw new IOException("TODO: handle sym links");
                    }
                }
            }
            in.close();

        } else if (compressedFile.getName().endsWith(".zip")) {

            ZipInputStream in = new ZipInputStream(new FileInputStream(compressedFile));
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    File f = new File(targetDirectory, e.getName());
                    f.mkdirs();
                    if (targetDir == null) {
                        targetDir = f;
                    }
                } else {
                    FileOutputStream out = new FileOutputStream(new File(targetDirectory, e.getName()));
                    byte[] buf = new byte[1024];
                    int l;
                    while ((l = in.read(buf, 0, 1024)) != -1) {
                        out.write(buf, 0, l);
                    }
                    out.close();
                }
                in.closeEntry();
            }
            in.close();

        }
        return targetDir;
    }

    private static boolean deleteDirectory(File path) {
        if (path.exists()) {
            File[] files = path.listFiles();
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        return (path.delete());
    }

    private static String join(List<String> entries, String sep) {
        StringBuilder b = new StringBuilder();
        for (String entry : entries) {
            if (b.length() > 0) {
                b.append(sep);
            }
            b.append(entry);
        }
        return b.toString();
    }

    private static void collectZipFiles(List<String> collectedZips,
            String rootDir,
            String version,
            String os,
            String arch) {
        File versionDir = new File(rootDir, version);
        if (versionDir.exists() && versionDir.isDirectory()) {
            for (File f : versionDir.listFiles()) {
                if (f.getName().equals(os)) {
                    for (File fOs : f.listFiles()) {
                        if (fOs.isDirectory()) {
                            if (fOs.getName().equals(arch)) {
                                for (File fArch : fOs.listFiles()) {
                                    if (fArch.isFile() && fArch.getName().endsWith(".zip")) {
                                        collectedZips.add(toZipString(fArch));
                                    }
                                }
                            }
                        } else if (fOs.isFile() && fOs.getName().endsWith(".zip")) {
                            collectedZips.add(toZipString(fOs));
                        }
                    }
                }
                if (f.isFile() && f.getName().endsWith(".zip")) {
                    collectedZips.add(toZipString(f));
                }
            }
        }
    }

    private static String toZipString(File zipFile) {
        String dir = zipFile.getParentFile().getAbsolutePath();
        String name = zipFile.getName();
        return String.format("jar:file:%s/%s!/", dir, name);
    }

    public void buildDistros() throws DistroBuildException {
        File targetDir = new File(targetDirectory);
        if (targetDir.exists() && targetDir.isDirectory()) {
            for (File versionDir : targetDir.listFiles(DIRS)) {
                for (File osDir : versionDir.listFiles(DIRS)) {
                    for (File archDir : osDir.listFiles(DIRS)) {
                        buildDistro(archDir, versionDir.getName(), osDir.getName(), archDir.getName());
                    }
                }
            }
        }
    }

    public String getTargetDirectory() {
        return targetDirectory;
    }

    public void setTargetDirectory(String targetDirectory) {
        this.targetDirectory = targetDirectory;
    }

    public String getP2DirectorExecutable() {
        return p2DirectorExecutable;
    }

    public void setP2DirectorExecutable(String p2DirectorExecutable) {
        this.p2DirectorExecutable = p2DirectorExecutable;
    }

    public String getStaticReposDirectory() {
        return staticReposDirectory;
    }

    public void setStaticReposDirectory(String reposDirectory) {
        this.staticReposDirectory = reposDirectory;
    }

    public String getBuildDirectory() {
        return buildDirectory;
    }

    public void setBuildDirectory(String buildDirectory) {
        this.buildDirectory = buildDirectory;
    }

    public void addInstallUnit(InstallUnit unit) {
        this.iuList.add(unit);
    }

    public void addP2Repository(P2Repository repo) {
        this.repoList.add(repo);
    }

    public void addUpdateSite(UpdateSite site) {
        this.siteList.add(site);
    }

    public String getDistDirectory() {
        return distDirectory;
    }

    public void setDistDirectory(String distDirectory) {
        this.distDirectory = distDirectory;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getAppDefinition() {
        return appDefinition;
    }

    public void setAppDefinition(String appDefinition) {
        this.appDefinition = appDefinition;
    }

}
