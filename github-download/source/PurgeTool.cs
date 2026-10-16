using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows.Forms;

internal static class PurgeTool
{
    private const string MarkerName = ".big-fucking-map-root";
    private const string MarkerText = "BIG-FUCKING-MAP-BUNDLE-ROOT-v1-8F6A6D0E";

    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "--purge-root")
        {
            RunWorker(args[1]);
            return;
        }

        string root = GetBundleRoot();
        string reason;
        if (!IsSafeBundle(root, out reason))
        {
            if (args.Contains("--self-test")) Environment.Exit(2);
            MessageBox.Show(reason, "Purge refused", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        if (args.Contains("--self-test")) Environment.Exit(0);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new PurgeForm(root));
    }

    private static string GetBundleRoot()
    {
        string executable = Application.ExecutablePath;
        DirectoryInfo toolFolder = Directory.GetParent(executable);
        DirectoryInfo bundle = toolFolder == null ? null : toolFolder.Parent;
        return bundle == null ? string.Empty : bundle.FullName;
    }

    private static bool IsSafeBundle(string candidate, out string reason)
    {
        reason = string.Empty;
        if (string.IsNullOrWhiteSpace(candidate))
        {
            reason = "The bundle folder could not be found.";
            return false;
        }

        string root;
        try { root = Path.GetFullPath(candidate).TrimEnd(Path.DirectorySeparatorChar); }
        catch
        {
            reason = "The bundle path is invalid.";
            return false;
        }

        if (!Directory.Exists(root))
        {
            reason = "The bundle folder no longer exists.";
            return false;
        }

        if ((new DirectoryInfo(root).Attributes & FileAttributes.ReparsePoint) != 0)
        {
            reason = "The bundle folder is a link. Purge will not follow linked folders.";
            return false;
        }

        string driveRoot = Path.GetPathRoot(root).TrimEnd(Path.DirectorySeparatorChar);
        if (string.Equals(root, driveRoot, StringComparison.OrdinalIgnoreCase))
        {
            reason = "Purge will never remove a drive root.";
            return false;
        }

        string[] forbidden =
        {
            Environment.GetFolderPath(Environment.SpecialFolder.Windows),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            Environment.GetFolderPath(Environment.SpecialFolder.Desktop),
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments)
        };
        foreach (string path in forbidden.Where(p => !string.IsNullOrWhiteSpace(p)))
        {
            if (string.Equals(root, Path.GetFullPath(path).TrimEnd(Path.DirectorySeparatorChar),
                              StringComparison.OrdinalIgnoreCase))
            {
                reason = "Purge refuses to remove Windows, program, profile, desktop, or documents folders.";
                return false;
            }
        }

        string marker = Path.Combine(root, MarkerName);
        if (!File.Exists(marker))
        {
            reason = "The exact Big Map bundle marker is missing. Nothing was deleted.";
            return false;
        }

        string value;
        try { value = File.ReadAllText(marker).Trim(); }
        catch (Exception ex)
        {
            reason = "The bundle marker could not be read: " + ex.Message;
            return false;
        }
        if (!string.Equals(value, MarkerText, StringComparison.Ordinal))
        {
            reason = "The bundle marker does not match. Nothing was deleted.";
            return false;
        }
        return true;
    }

    private static void RunWorker(string root)
    {
        string reason;
        if (!IsSafeBundle(root, out reason))
        {
            MessageBox.Show(reason, "Purge refused", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        Thread.Sleep(1500);
        try
        {
            ClearReadOnly(root);
            Directory.Delete(root, true);
            MessageBox.Show("The Big Map bundle was removed.", "Purge complete",
                            MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show("Purge could not remove the complete bundle.\r\n\r\n" + ex.Message,
                            "Purge stopped", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            ScheduleSelfDelete();
        }
    }

    private static void ClearReadOnly(string root)
    {
        foreach (string file in Directory.GetFiles(root, "*", SearchOption.AllDirectories))
            File.SetAttributes(file, FileAttributes.Normal);
        foreach (string directory in Directory.GetDirectories(root, "*", SearchOption.AllDirectories))
            new DirectoryInfo(directory).Attributes &= ~FileAttributes.ReadOnly;
    }

    private static void ScheduleSelfDelete()
    {
        string exe = Application.ExecutablePath;
        Process.Start(new ProcessStartInfo
        {
            FileName = "cmd.exe",
            Arguments = "/d /c ping 127.0.0.1 -n 3 >nul & del /f /q \"" + exe + "\"",
            CreateNoWindow = true,
            UseShellExecute = false,
            WindowStyle = ProcessWindowStyle.Hidden
        });
    }

    private sealed class PurgeForm : Form
    {
        private readonly string root;
        private readonly CheckBox acknowledgement;
        private readonly Button purge;

        internal PurgeForm(string rootFolder)
        {
            root = rootFolder;
            Text = "Remove Big Map bundle";
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(620, 300);
            MinimumSize = new Size(560, 330);
            MaximizeBox = false;
            Font = new Font("Segoe UI", 10F);

            Label title = new Label
            {
                Text = "Permanently remove this downloaded bundle?",
                Font = new Font(Font, FontStyle.Bold),
                AutoSize = true,
                Location = new Point(24, 22)
            };
            Label warning = new Label
            {
                Text = "This deletes Districts, both generators, maps, settings, caches, and every other file inside this exact folder. This cannot be undone.",
                AutoSize = false,
                Location = new Point(24, 58),
                Size = new Size(570, 58)
            };
            TextBox path = new TextBox
            {
                Text = root,
                ReadOnly = true,
                Location = new Point(24, 124),
                Size = new Size(570, 27)
            };
            acknowledgement = new CheckBox
            {
                Text = "I understand that every file in the folder shown above will be permanently deleted.",
                AutoSize = true,
                Location = new Point(24, 172)
            };
            purge = new Button
            {
                Text = "Permanently delete bundle",
                Enabled = false,
                AutoSize = true,
                Location = new Point(24, 220)
            };
            Button cancel = new Button
            {
                Text = "Cancel",
                AutoSize = true,
                DialogResult = DialogResult.Cancel,
                Location = new Point(470, 220)
            };

            acknowledgement.CheckedChanged += delegate { purge.Enabled = acknowledgement.Checked; };
            purge.Click += PurgeClicked;
            Controls.AddRange(new Control[] { title, warning, path, acknowledgement, purge, cancel });
            CancelButton = cancel;
        }

        private void PurgeClicked(object sender, EventArgs e)
        {
            DialogResult answer = MessageBox.Show(
                "Final confirmation:\r\n\r\nPermanently delete this complete bundle?\r\n" + root,
                "Delete the Big Map bundle", MessageBoxButtons.YesNo, MessageBoxIcon.Warning,
                MessageBoxDefaultButton.Button2);
            if (answer != DialogResult.Yes) return;

            string reason;
            if (!IsSafeBundle(root, out reason))
            {
                MessageBox.Show(reason, "Purge refused", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            string tempFolder = Path.Combine(Path.GetTempPath(), "BigMapPurge-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(tempFolder);
            string worker = Path.Combine(tempFolder, "BigMapPurge.exe");
            File.Copy(Application.ExecutablePath, worker, true);
            Process.Start(new ProcessStartInfo
            {
                FileName = worker,
                Arguments = "--purge-root \"" + root + "\"",
                UseShellExecute = false,
                WorkingDirectory = tempFolder
            });
            Close();
        }
    }
}
