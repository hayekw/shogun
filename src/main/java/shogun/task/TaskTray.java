package shogun.task;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import shogun.logging.LoggerFactory;
import shogun.sdk.SDK;
import shogun.sdk.SDKLauncher;
import shogun.sdk.Version;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static javax.swing.JOptionPane.QUESTION_MESSAGE;

public class TaskTray {
    private final static Logger logger = LoggerFactory.getLogger();
    private ResourceBundle bundle = ResourceBundle.getBundle("message", Locale.getDefault());

    private SDK sdk = new SDK();
    private SystemTray tray;
    private TrayIcon icon;
    // for the test purpose, set true to skip confirmation dialogs
    boolean skipConfirmation = false;
    PopupMenu popup = new PopupMenu();
    Menu availableCandidatesMenu = new Menu(getMessage(Messages.availableCandidates));
    MenuItem versionMenu = new MenuItem();
    private MenuItem quitMenu = new MenuItem(getMessage(Messages.quit));

    private final Frame thisFrameMakesDialogsAlwaysOnTop = new Frame();
    private List<Image> animatedDuke;
    private DukeThread duke;

    public TaskTray() {
        animatedDuke = new ArrayList<>();
        logger.debug("Loading Duke images.");
        for (int i = 0; i < 12; i++) {
            Image image = Toolkit.getDefaultToolkit().createImage(ClassLoader.getSystemResource("images/duke-64x64-anim" + i + ".png"));
            animatedDuke.add(image);
        }
        duke = new DukeThread();
        invokeLater(() -> {
            popup.add(availableCandidatesMenu);

            versionMenu.addActionListener(e -> versionMenuClicked());
            popup.add(versionMenu);

            quitMenu.addActionListener(e -> quit());
            popup.add(quitMenu);
        });
    }


    private void invokeLater(Runnable runnable) {
        duke.startRoll();
        EventQueue.invokeLater(() -> {
                    runnable.run();
                    duke.stopRoll();
                }
        );
    }

    private void execute(Runnable runnable) {
        duke.startRoll();
        executorService.execute(() -> {
                    runnable.run();
                    duke.stopRoll();
                }
        );
    }

    private void versionMenuClicked() {
        if (sdk.isInstalled()) {
            execute(this::initializeMenuItems);

        } else {
            installSDKMAN();
        }
    }

    private CountDownLatch dukeLatch = new CountDownLatch(0);

    void waitForActionToFinish() throws InterruptedException {
        dukeLatch.await(60, TimeUnit.SECONDS);
    }

    class DukeThread extends Thread {
        AtomicInteger integer = new AtomicInteger();


        DukeThread() {
            setName("Duke roller");
            setDaemon(true);
        }

        private synchronized void startRoll() {
            if (integer.getAndIncrement() == 0) {
                dukeLatch = new CountDownLatch(1);
            }
            synchronized (this) {
                this.notify();
            }
        }

        private synchronized void stopRoll() {
            if (integer.decrementAndGet() == 0) {
                synchronized (this) {
                    this.notify();
                }
                dukeLatch.countDown();
            }
        }

        public void run() {
            //noinspection InfiniteLoopStatement
            while (true) {
                while (0 < integer.get()) {
                    for (Image animation : animatedDuke) {
                        invokeLater(() -> icon.setImage(animation));
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignore) {
                        }
                        if (0 == integer.get()) {
                            break;
                        }
                    }
                }
                invokeLater(() -> icon.setImage(animatedDuke.get(0)));
                try {
                    synchronized (this) {
                        wait();
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private ImageIcon dialogIcon = new ImageIcon(Toolkit.getDefaultToolkit().createImage(ClassLoader.getSystemResource("images/duke-128x128.png")));

    private final ExecutorService executorService = Executors.newFixedThreadPool(1,
            new ThreadFactory() {
                int count = 0;

                @Override
                public Thread newThread(@NotNull Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName(String.format("Shogun Executor[%d]", count++));
                    thread.setDaemon(true);
                    return thread;
                }
            }
    );


    public void show() {
        thisFrameMakesDialogsAlwaysOnTop.setAlwaysOnTop(true);
        EventQueue.invokeLater(() -> {
            logger.debug("Preparing task tray.");
            tray = SystemTray.getSystemTray();
            icon = new TrayIcon(animatedDuke.get(0), "Shogun", popup);
            icon.setImageAutoSize(true);
            try {
                tray.add(icon);
            } catch (AWTException e) {
                quit();
            }
        });
        duke.start();
        execute(this::initializeMenuItems);
    }

    private void quit() {
        invokeLater(() -> tray.remove(icon));
        System.exit(0);
    }

    private Map<String, Candidate> candidateMap = new HashMap<>();

    private synchronized void initializeMenuItems() {
        logger.debug("Initializing menu items.");
        setVersionMenuLabel();

        List<String> installedCandidates = new ArrayList<>();
        if (sdk.isInstalled()) {
            sdk.getInstalledCandidates()
                    .forEach(e -> {
                        logger.debug("Installed candidate: {}", e);
                        installedCandidates.add(e);
                        candidateMap.computeIfAbsent(e, e2 -> new Candidate(e, true));
                    });
        }
        if (!sdk.isOffline()) {
            logger.debug("Offline mode.");
            // list available candidates
            sdk.listCandidates().stream()
                    .filter(e -> !installedCandidates.contains(e))
                    .forEach(e -> {
                        logger.debug("Available candidate: {}", e);
                        installedCandidates.add(e);
                        candidateMap.computeIfAbsent(e, e2 -> new Candidate(e, false));
                    });
        }
        installedCandidates.forEach(e -> candidateMap.get(e).setVersions());
    }

    private void setVersionMenuLabel() {
        String label;
        if (sdk.isInstalled()) {
            String version = sdk.getVersion();
            logger.debug("SDKMAN! version {} detected.", version);
            label = version + (sdk.isOffline() ? " (offline)" : "");
        } else {
            logger.debug("SDKMAN! not installed.");
            label = getMessage(Messages.installSDKMan);
        }
        invokeLater(() -> versionMenu.setLabel(label));
    }

    class Candidate {
        private final String candidate;
        private List<Version> versions;
        Menu candidateMenu;

        Candidate(String candidate, boolean installed) {
            this.candidate = candidate;
            logger.debug("Building menu for : {}", candidate);
            candidateMenu = new Menu(candidate);
            if (installed) {
                addToInstalledCandidatesMenu(candidateMenu);
            } else {
                addToAvailableCandidatesMenu(candidateMenu);
            }
        }

        void setVersions() {
            this.versions = new ArrayList<>();
            List<Version> versions = sdk.list(candidate);
            versions.stream().filter(e -> e.isInstalled() || e.isLocallyInstalled()).forEach(e -> this.versions.add(e));
            versions.stream().filter(e -> !e.isInstalled() && !e.isLocallyInstalled()).forEach(e -> this.versions.add(e));
            refreshMenus();
        }

        void refreshMenus() {
            invokeLater(() -> {
                setRootMenuLabel(candidateMenu);
                candidateMenu.removeAll();

                for (Version version : versions) {
                    Menu menu = new Menu(toLabel(version));
                    updateMenu(menu, version);
                    candidateMenu.add(menu);
                }
            });
        }

        private boolean isInstalled() {
            return versions.stream().anyMatch(e -> e.isInstalled() || e.isLocallyInstalled());
        }

        void setRootMenuLabel(Menu menu) {
            // add version string in use
            invokeLater(() ->
                    versions.stream()
                            .filter(Version::isUse)
                            .findFirst()
                            .ifPresentOrElse(e -> menu.setLabel(candidate + " > " + e.toString()),
                                    () -> menu.setLabel(candidate)));
        }

        Menu find(Menu menu, Version version) {
            for (int i = 0; i < menu.getItemCount(); i++) {
                Menu item = (Menu) menu.getItem(i);
                if (item.getLabel().contains(version.toString())) {
                    return item;
                }
            }
            throw new IllegalStateException("menu not found");
        }

        void setDefault(Version version) {
            execute(() -> {
                logger.debug("Set default: {}", version);
                sdk.makeDefault(version.getCandidate(), version);
                Menu menu = candidateMenu;
                Optional<Version> lastDefault = versions.stream().filter(Version::isUse).findFirst();
                lastDefault.ifPresent(oldDefaultVersion -> {
                    oldDefaultVersion.setUse(false);
                    invokeLater(() -> {
                        Menu oldDefaultMenu = find(menu, oldDefaultVersion);
                        updateMenu(oldDefaultMenu, oldDefaultVersion);
                    });
                });

                Version newDefaultVersion = versions.get(versions.indexOf(version));
                newDefaultVersion.setUse(true);
                Menu newDefaultMenu = find(menu, newDefaultVersion);
                invokeLater(() -> updateMenu(newDefaultMenu, newDefaultVersion));
                setRootMenuLabel(menu);
            });
        }

        void install(Version version) {
            execute(() -> {
                logger.debug("Install: {}", version);
                int response = skipConfirmation ? JOptionPane.OK_OPTION :
                        JOptionPane.showConfirmDialog(thisFrameMakesDialogsAlwaysOnTop,
                                getMessage(Messages.confirmInstallMessage, version.getCandidate(), version.toString()),
                                getMessage(Messages.confirmInstallTitle, version.getCandidate(), version.toString()), JOptionPane.OK_CANCEL_OPTION,
                                QUESTION_MESSAGE, dialogIcon);
                if (response == JOptionPane.OK_OPTION) {
                    var wasInstalled = isInstalled();
                    sdk.install(version);
                    refreshMenus();
                    if (!wasInstalled) {
                        // this candidate wasn't installed. move to installed candidates list
                        Menu candidateRootMenu = candidateMenu;
                        invokeLater(() -> availableCandidatesMenu.remove(candidateRootMenu));
                        addToInstalledCandidatesMenu(candidateRootMenu);
                    }
                }
            });
        }

        void removeArchive(Version version) {
            execute(() -> {
                logger.debug("Delete Archive: {}", version);
                version.removeArchive();
                refreshMenus();
            });
        }

        void uninstall(Version version) {
            execute(() -> {
                logger.debug("Uninstall: {}", version);
                int response = skipConfirmation ? JOptionPane.OK_OPTION :
                        JOptionPane.showConfirmDialog(thisFrameMakesDialogsAlwaysOnTop,
                                getMessage(Messages.confirmUninstallMessage, version.getCandidate(), version.toString()),
                                getMessage(Messages.confirmUninstallTitle, version.getCandidate(), version.toString()), JOptionPane.OK_CANCEL_OPTION,
                                QUESTION_MESSAGE, dialogIcon);
                if (response == JOptionPane.OK_OPTION) {
                    var wasInstalled = isInstalled();
                    sdk.uninstall(version);
                    refreshMenus();
                    if (wasInstalled && !isInstalled()) {
                        // no version of this candidate is installed anymore. move to available candidates list
                        Menu candidateRootMenu = candidateMenu;
                        invokeLater(() -> popup.remove(candidateRootMenu));
                        addToAvailableCandidatesMenu(candidateRootMenu);
                    }
                }
            });
        }

        void addToInstalledCandidatesMenu(Menu menu) {
            boolean added = false;
            for (int i = 0; i < popup.getItemCount() - 3; i++) {
                MenuItem item = popup.getItem(i);
                if (0 < item.getLabel().compareTo(candidate)) {
                    int index = i;
                    invokeLater(() -> popup.insert(menu, index));
                    added = true;
                    break;
                }
            }
            if (!added) {
                // last item in installed candidates items
                invokeLater(() -> popup.insert(menu, popup.getItemCount() - 3));
            }
        }

        void addToAvailableCandidatesMenu(Menu menu) {
            boolean added = false;
            for (int i = 0; i < availableCandidatesMenu.getItemCount(); i++) {
                Menu item = (Menu) availableCandidatesMenu.getItem(i);
                if (0 < item.getLabel().compareTo(candidate)) {
                    int index = i;
                    invokeLater(() -> availableCandidatesMenu.insert(menu, index));
                    added = true;
                    break;
                }
            }
            if (!added) {
                invokeLater(() -> availableCandidatesMenu.add(menu));
            }
        }

        // needs to be called inside GUI thread
        private void updateMenu(Menu menu, Version version) {
            menu.setLabel(toLabel(version));
            menu.removeAll();
            if (version.isInstalled() || version.isLocallyInstalled()) {
                if (!version.isUse()) {
                    MenuItem menuItem = new MenuItem(getMessage(Messages.makeDefault));
                    menuItem.addActionListener(e -> setDefault(version));
                    menu.add(menuItem);
                }

                MenuItem openInTerminalMenu = new MenuItem(getMessage(Messages.openInTerminal, version.getIdentifier()));
                openInTerminalMenu.addActionListener(e -> openInTerminal(version));
                menu.add(openInTerminalMenu);

                MenuItem copyPathMenu = new MenuItem(getMessage(Messages.copyPath));
                copyPathMenu.addActionListener(e -> copyPathToClipboard(version));
                menu.add(copyPathMenu);

                MenuItem revealInFinderMenu = new MenuItem(getMessage(Messages.revealInFinder));
                revealInFinderMenu.addActionListener(e -> revealInFinder(version));
                menu.add(revealInFinderMenu);
            }

            if (version.isArchived()) {
                MenuItem menuItem = new MenuItem(getMessage(Messages.removeArchive, version.getArchiveSize()));
                menuItem.addActionListener(e -> removeArchive(version));
                menu.add(menuItem);
            }

            if (version.isInstalled() || version.isLocallyInstalled()) {
                MenuItem uninstallItem = new MenuItem(getMessage(Messages.uninstall));
                uninstallItem.addActionListener(e -> uninstall(version));
                menu.add(uninstallItem);
            }

            if (!version.isInstalled() && !version.isLocallyInstalled()) {
                MenuItem menuItem = new MenuItem(getMessage(Messages.install));
                menuItem.addActionListener(e -> install(version));
                menu.add(menuItem);
            }
        }
    }

    private void installSDKMAN() {
        execute(() -> {
            sdk.install();
            invokeLater(this::initializeMenuItems);
        });
    }

    private void openInTerminal(Version version) {
        execute(() -> SDKLauncher.exec("bash",
                "-c", String.format("osascript -e 'tell application \"Terminal\" to do script \"sdk use %s %s\"';osascript -e 'tell application \"Terminal\" to activate'",
                        version.getCandidate(), version.getIdentifier())));
    }

    private void copyPathToClipboard(Version version) {
        execute(() -> {
            Toolkit kit = Toolkit.getDefaultToolkit();
            Clipboard clip = kit.getSystemClipboard();
            StringSelection ss = new StringSelection(version.getPath());
            clip.setContents(ss, ss);
        });
    }

    private void revealInFinder(Version version) {
        execute(() -> {
            ProcessBuilder pb = new ProcessBuilder("open", version.getPath());
            try {
                Process process = pb.start();
                process.waitFor();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private static String toLabel(Version version) {
        String label = version.isUse() ? ">" : "  ";
        label += version.toString();
        if (version.isLocallyInstalled()) {
            label += " (local only)";
        } else if (version.isInstalled()) {
            label += " (installed)";
        }
        return label;
    }

    String getMessage(Messages message, String... values) {
        MessageFormat formatter = new MessageFormat(bundle.getString(message.name()));
        return formatter.format(values);
    }
}