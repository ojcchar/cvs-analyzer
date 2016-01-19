package seers.cvsanalyzer.git;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Deprecated
public class GitUtilities {

	private static final String GIT_COMMAND = "git";
	private static final long timeout = 300000;// 5 mins
	private static Logger LOGGER = LoggerFactory.getLogger(GitUtilities.class);

	public static int cloneGitRepository(String repositoryAddress, String destinationFolder)
			throws IOException, InterruptedException {

		Runtime rt = Runtime.getRuntime();
		String cmd = GIT_COMMAND + " clone " + repositoryAddress + " " + destinationFolder;
		Process process = rt.exec(cmd);

		String line = null;
		BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		while ((line = stdoutReader.readLine()) != null) {
			LOGGER.info(line);
		}
		stdoutReader.close();

		BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
		while ((line = stderrReader.readLine()) != null) {
			LOGGER.info(line);
		}
		stderrReader.close();

		// -------------------

		Worker worker = new Worker(process);
		worker.start();
		try {
			worker.join(timeout);
			if (worker.exit != null)
				return worker.exit;
			else
				return 1;
		} catch (InterruptedException ex) {
			worker.interrupt();
			throw ex;
		} finally {
			process.destroy();
		}

	}

	private static class Worker extends Thread {
		private final Process process;
		private Integer exit;

		private Worker(Process process) {
			this.process = process;
		}

		public void run() {
			try {
				exit = process.waitFor();
			} catch (InterruptedException ignore) {
				return;
			}
		}
	}

	public static int checkoutToTag(String repositoryPath, String tag) throws IOException, InterruptedException {

		// [START] Make the file executable (i.e., adds execution
		// permissions to the created file)
		Runtime rt = Runtime.getRuntime();
		String cmd = GIT_COMMAND + " --git-dir \"" + repositoryPath + File.separator + ".git\" --work-tree "
				+ repositoryPath + " checkout " + tag;
		Process process = rt.exec(cmd);

		String line = null;
		BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		while ((line = stdoutReader.readLine()) != null) {
			LOGGER.info(line);
		}
		stdoutReader.close();

		BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
		while ((line = stderrReader.readLine()) != null) {
			LOGGER.info(line);
		}
		stderrReader.close();

		process.waitFor();
		return process.exitValue();
	}

	public static int saveLogFromGitRepository(String logFilePath, String repositoryPath, String tagName)
			throws IOException, InterruptedException {

		// [START] Make the file executable (i.e., adds execution
		// permissions to the created file)
		String tag = tagName == null ? "HEAD" : tagName;
		String arguments = " " + "--git-dir" + " \"" + repositoryPath + File.separator + ".git\"" + " " + "log" + " "
		// + "--first-parent"
				+ " --name-status" + " " + "--date=iso" + " " + "--stat" + " " + "HEAD" + " "
				+ "--pretty=format:\"<commit-id>%h</commit-id><author-email>%ae</author-email><author-date>%ad</author-date><committer-email>%ce</committer-email><committer-date>%cd</committer-date><message>%s</message>\""
				+ " " + tag;
		String cmd = GIT_COMMAND + arguments;
		LOGGER.debug(cmd);

		// Runtime rt = Runtime.getRuntime();
		// Process process = rt.exec(cmd);

		// ----
		ProcessBuilder builder = new ProcessBuilder(GIT_COMMAND,
				// "--git-dir", repositoryPath + File.separator + ".git",
				"log",
				// "--first-parent",
				"--name-status", "--date=iso", "--stat", "HEAD",
				"--pretty=format:\"<commit-id>%h</commit-id><author-email>%ae</author-email><author-date>%ad</author-date><committer-email>%ce</committer-email><committer-date>%cd</committer-date><message>%s</message>\"",
				tag);
		LOGGER.debug(builder.command().toString());
		builder.redirectErrorStream(true);
		File absoluteFile = new File(repositoryPath).getAbsoluteFile();
		builder.directory(absoluteFile);
		builder.redirectErrorStream(true);
		Process process = builder.start();

		// ------------

		// Scanner s = new Scanner(process.getInputStream());
		// StringBuilder text = new StringBuilder();
		// while (s.hasNextLine()) {
		// text.append(s.nextLine());
		// text.append("\n");
		// }
		// s.close();
		//
		// int result = process.waitFor();
		//
		// System.out.printf("Process exited with result %d and output %s%n",
		// result, text);
		//
		// return result;

		// ---

		String line = null;

		BufferedReader stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		PrintStream printStream = new PrintStream(new FileOutputStream(logFilePath));
		while ((line = stdoutReader.readLine()) != null) {
			printStream.append(line);
			printStream.append("\n");
		}
		printStream.close();

		BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
		while ((line = stderrReader.readLine()) != null) {
			LOGGER.info(line);
		}

		return process.waitFor();

	}

	/**
	 * This method reads a log file stored on your machine, parses it and
	 * encapsulates the results in a Vector of CommitBean objects.
	 * 
	 * @param logFilePath
	 * @return
	 * @throws IOException
	 * @throws ParseException
	 */
	public static Vector<CommitBean> readCommits(String logFilePath) throws IOException, ParseException {
		Vector<CommitBean> result = new Vector<CommitBean>();

		// [START] We prepare the regular expression needed to parse the log
		// file
		String commitIdRegex = Pattern.quote("<commit-id>") + Pattern.compile("(.*?)") + Pattern.quote("</commit-id>");
		Pattern commitIdPattern = Pattern.compile(commitIdRegex, Pattern.DOTALL);

		String authorEmailRegex = Pattern.quote("<author-email>") + Pattern.compile("(.*?)")
				+ Pattern.quote("</author-email>");
		Pattern authorEmailPattern = Pattern.compile(authorEmailRegex, Pattern.DOTALL);

		String authorDateRegex = Pattern.quote("<author-date>") + Pattern.compile("(.*?)")
				+ Pattern.quote("</author-date>");
		Pattern authorDatePattern = Pattern.compile(authorDateRegex, Pattern.DOTALL);

		String committerEmailRegex = Pattern.quote("<committer-email>") + Pattern.compile("(.*?)")
				+ Pattern.quote("</committer-email>");
		Pattern committerEmailPattern = Pattern.compile(committerEmailRegex, Pattern.DOTALL);

		String committerDateRegex = Pattern.quote("<committer-date>") + Pattern.compile("(.*?)")
				+ Pattern.quote("</committer-date>");
		Pattern committerDatePattern = Pattern.compile(committerDateRegex, Pattern.DOTALL);

		String messageRegex = Pattern.quote("<message>") + Pattern.compile("(.*?)") + Pattern.quote("</message>");
		Pattern messagePattern = Pattern.compile(messageRegex, Pattern.DOTALL);

		Pattern spaces = Pattern.compile("\\s+");
		// [END]

		// Needed to convert the date (in the format used in the log) into a
		// date object
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");

		BufferedReader br = new BufferedReader(new FileReader(logFilePath));
		String line = null;
		CommitBean commitToAdd = null;
		String[] tokens = null;
		Vector<String> modifiedFiles = null;
		Vector<String> addedFiles = null;
		Vector<String> deletedFiles = null;

		boolean hasData = false;
		// [START] read the log file line by line
		while ((line = br.readLine()) != null) {

			if (line.startsWith("<commit-id>")) {
				hasData = true;
				// if line starts with <commit-id> this means that I have to
				// store the previous commit (if this is not the first commit
				// in the log file) and create a new commit by setting its
				// properties (commitId, authorEmail, date, commit message)

				if (commitToAdd != null) {
					// this is not the first commit in the log file
					commitToAdd.setModifiedFiles(modifiedFiles);
					commitToAdd.setAddedFiles(addedFiles);
					commitToAdd.setDeletedFiles(deletedFiles);
					result.add(commitToAdd);
				}

				// create the new commit to add, and initializes
				// the vectors containing the modified files
				commitToAdd = new CommitBean();
				modifiedFiles = new Vector<String>();
				addedFiles = new Vector<String>();
				deletedFiles = new Vector<String>();

				// set the commitId
				Matcher matcherCommitId = commitIdPattern.matcher(line);
				if (matcherCommitId.find()) {
					String commitId = matcherCommitId.group(1);
					commitToAdd.setCommitId(commitId);
				}

				// set the author email
				Matcher matcherAuthorEmail = authorEmailPattern.matcher(line);
				if (matcherAuthorEmail.find())
					commitToAdd.setAuthorEmail(matcherAuthorEmail.group(1));

				// set the date
				Matcher matcherAuthorDate = authorDatePattern.matcher(line);
				if (matcherAuthorDate.find())
					commitToAdd.setDate(dateFormat.parse(matcherAuthorDate.group(1)));

				// set the author email
				Matcher matcherCommitterEmail = committerEmailPattern.matcher(line);
				if (matcherCommitterEmail.find())
					commitToAdd.setCommitterEmail(matcherCommitterEmail.group(1));

				// set the date
				Matcher matcherCommitterDate = committerDatePattern.matcher(line);
				if (matcherCommitterDate.find())
					commitToAdd.setCommitterDate(dateFormat.parse(matcherCommitterDate.group(1)));

				// set the commit message
				Matcher matcherMessage = messagePattern.matcher(line);
				if (matcherMessage.find())
					commitToAdd.setCommitMessage(matcherMessage.group(1));

			} else if (line.startsWith("A")) {
				hasData = true;
				// this file has been added in the commit
				tokens = spaces.split(line);
				addedFiles.add(tokens[1]);
			} else if (line.startsWith("D")) {
				hasData = true;
				// this file has been deleted in the commit
				tokens = spaces.split(line);
				deletedFiles.add(tokens[1]);
			} else if (line.startsWith("M")) {
				hasData = true;
				// this file has been modified in the commit
				tokens = spaces.split(line);
				modifiedFiles.add(tokens[1]);
			}

		}
		br.close();
		// [END] read the log file line by line

		if (!hasData) {
			throw new RuntimeException("The log has no valid data!");
		}

		// I still need to add the last commit that I read
		commitToAdd.setModifiedFiles(modifiedFiles);
		commitToAdd.setAddedFiles(addedFiles);
		commitToAdd.setDeletedFiles(deletedFiles);
		result.add(commitToAdd);

		return result;
	}

}
