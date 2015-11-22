package seers.cvsanalyzer.git;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Vector;

import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitUtilities2 {

	private static Logger LOGGER = LoggerFactory.getLogger(GitUtilities2.class);

	public static void cloneGitRepository(String repositoryAddress, String destinationFolder)
			throws InvalidRemoteException, TransportException, GitAPIException {
		File directory = new File(destinationFolder);
		if (!directory.exists()) {
			Git git = Git.cloneRepository().setURI(repositoryAddress).setDirectory(directory).call();
			git.close();
		} else {
			LOGGER.debug("Not cloning, directory already exists: " + directory);
		}
	}

	public static void checkout(String repositoryPath, String revision) throws Exception {
		Repository repository = builRepository(repositoryPath);

		try (Git git = new Git(repository)) {
			git.checkout().setName(revision).call();
		}
	}

	public static RevCommit getRevision(String repositoryPath, String revision) throws Exception {
		Repository repository = builRepository(repositoryPath);
		return getRevision(revision, repository);
	}

	public static RevCommit getRevision(String revision, Repository repository)
			throws AmbiguousObjectException, IncorrectObjectTypeException, IOException, MissingObjectException {
		ObjectId commitId = repository.resolve(revision);
		try (RevWalk revWalk = new RevWalk(repository)) {
			RevCommit commit = revWalk.parseCommit(commitId);
			return commit;
		}
	}

	public static Repository builRepository(String repositoryPath) throws IOException {
		FileRepositoryBuilder builder = new FileRepositoryBuilder();
		Repository repository = builder.setGitDir(new File(repositoryPath + File.separator + ".git")).readEnvironment()
				.findGitDir().build();
		return repository;
	}

	public static Vector<CommitBean> readCommits(String repositoryPath, String tagName) throws Exception {

		Repository repository = builRepository(repositoryPath);

		try (Git git = new Git(repository)) {
			LogCommand log = git.log();

			// --------------------------------

			if (tagName == null) {
				ObjectId objectId = repository.resolve(Constants.HEAD);
				if (objectId == null) {
					throw new Exception("Cannot find HEAD for " + repositoryPath);
				}
				log.add(objectId);
			} else {
				Ref ref = repository.getRef("refs/tags/" + tagName);
				Ref peeledRef = repository.peel(ref);
				if (peeledRef.getPeeledObjectId() != null) {
					log.add(peeledRef.getPeeledObjectId());
				} else {
					log.add(ref.getObjectId());
				}
			}

			// -------------------------------

			try (ObjectReader reader = repository.newObjectReader()) {

				Iterable<RevCommit> revs = log.call();
				Vector<CommitBean> commits = new Vector<>();

				AbstractTreeIterator prevTreeIter = null;
				DiffCommand diff = git.diff().setShowNameAndStatusOnly(true);

				for (RevCommit revCommit : revs) {
					CommitBean commit = new CommitBean();
					commit.setAuthorEmail(revCommit.getAuthorIdent().getEmailAddress());
					commit.setCommitterEmail(revCommit.getAuthorIdent().getEmailAddress());
					commit.setCommitId(revCommit.name());
					commit.setCommitterDate(revCommit.getAuthorIdent().getWhen());
					commit.setDate(revCommit.getAuthorIdent().getWhen());
					commit.setCommitMessage(revCommit.getFullMessage());

					prevTreeIter = setCommitFiles(commit, revCommit, diff, reader, prevTreeIter);

					commits.addElement(commit);

				}

				return commits;
			}
		}

	}

	private static AbstractTreeIterator setCommitFiles(CommitBean commit, RevCommit revCommit, DiffCommand diff,
			ObjectReader reader, AbstractTreeIterator prevTreeIter) throws Exception, IOException {

		AbstractTreeIterator oldTreeIter = null;
		CanonicalTreeParser newTreeIter = null;
		if (prevTreeIter instanceof CanonicalTreeParser) {
			newTreeIter = (CanonicalTreeParser) prevTreeIter;
		}

		int parentCount = revCommit.getParentCount();

		if (parentCount == 0) {
			oldTreeIter = new EmptyTreeIterator();
			newTreeIter = new CanonicalTreeParser(null, reader, revCommit.getTree());
		} else {

			// FIXME: should consider all the parents?
			RevCommit parent = revCommit.getParent(0);

			// old
			CanonicalTreeParser oldCanonTP = new CanonicalTreeParser();
			oldCanonTP.reset(reader, parent.getTree().getId());
			oldTreeIter = oldCanonTP;

			// new
			if (newTreeIter == null) {
				newTreeIter = new CanonicalTreeParser();
			}
			newTreeIter.reset(reader, revCommit.getTree().getId());
		}

		List<DiffEntry> diffs = diff.setNewTree(newTreeIter).setOldTree(oldTreeIter).call();
		for (DiffEntry entry : diffs) {
			// FIXME: process the remaining change types
			ChangeType type = entry.getChangeType();
			if (ChangeType.ADD.equals(type)) {
				commit.getAddedFiles().add(entry.getNewPath());
			} else if (ChangeType.MODIFY.equals(type)) {
				commit.getModifiedFiles().add(entry.getNewPath());
			} else if (ChangeType.DELETE.equals(type)) {
				commit.getDeletedFiles().add(entry.getNewPath());
			}

		}

		return oldTreeIter;
	}

}
