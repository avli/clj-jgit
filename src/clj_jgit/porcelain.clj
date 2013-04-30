(ns clj-jgit.porcelain
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-jgit.util :as util])
  (:use
    clj-jgit.internal)
  (:import [java.io FileNotFoundException File]
           [org.eclipse.jgit.lib RepositoryBuilder]
           [org.eclipse.jgit.api
            Git
            InitCommand StatusCommand AddCommand
            ListBranchCommand PullCommand MergeCommand LogCommand
            LsRemoteCommand Status]
           [org.eclipse.jgit.transport FetchResult]))

(declare log-builder)

(defn- guess-repo-path
  [^String path]
  (let [with-git (io/as-file (str path "/.git"))
        bare (io/as-file (str path "/refs"))] 
    (cond 
      (.endsWith path ".git") (io/as-file path)
      (.exists with-git) with-git
      (.exists bare) (io/as-file path))))

(defn load-repo
  "Given a path (either to the parent folder or to the `.git` folder itself), load the Git repository"
  (^org.eclipse.jgit.api.Git [path]
    (if-let [guessed-path (guess-repo-path path)]
      (-> (RepositoryBuilder.)
        (.setGitDir guessed-path)
        (.readEnvironment)
        (.findGitDir)
        (.build)
        (Git.))
      (throw 
        (FileNotFoundException. (str "The Git repository at '" path "' could not be located."))))))

(defmacro with-repo
  "Binds `repo` to a repository handle"
  [path & body]
  `(let [~'repo (load-repo ~path)
         ~'rev-walk (new-rev-walk ~'repo)]
     ~@body))

(defn git-add
  "The `file-pattern` is either a single file name (exact, not a pattern) or the name of a directory. If a directory is supplied, all files within that directory will be added. If `only-update?` is set to `true`, only files which are already part of the index will have their changes staged (i.e. no previously untracked files will be added to the index)."
  ([^Git repo file-pattern] 
    (git-add repo file-pattern false))
  ([^Git repo file-pattern only-update?]
     (-> repo
         (.add)
         (.addFilepattern file-pattern)
         (.setUpdate only-update?)
         (.call))))

(defn git-branch-list
  "Get a list of branches in the Git repo. Return the default objects generated by the JGit API."
  ([^Git repo] 
    (git-branch-list repo :local))
  ([^Git repo opt]
     (let [opt-val {:all org.eclipse.jgit.api.ListBranchCommand$ListMode/ALL
                    :remote org.eclipse.jgit.api.ListBranchCommand$ListMode/REMOTE}
           branches (if (= opt :local)
                      (-> repo
                          (.branchList)
                          (.call))
                      (-> repo
                          (.branchList)
                          (.setListMode (opt opt-val))
                          (.call)))]
       (seq branches))))

(defn git-branch-current* 
  [^Git repo] 
  (.getFullBranch (.getRepository repo)))

(defn git-branch-current
  "The current branch of the git repo"
  [^Git repo]
  (str/replace (git-branch-current* repo) #"^refs/heads/" ""))

(defn git-branch-attached?
  "Is the current repo on a branch (true) or in a detached HEAD state?"
  [^Git repo]
  (not (nil? (re-find #"^refs/heads/" (git-branch-current* repo)))))

(defn git-branch-create
  "Create a new branch in the Git repository."
  ([^Git repo branch-name]        
    (git-branch-create repo branch-name false nil))
  ([^Git repo branch-name force?] 
    (git-branch-create repo branch-name force? nil))
  ([^Git repo branch-name force? ^String start-point]
     (if (nil? start-point)
       (-> repo
           (.branchCreate)
           (.setName branch-name)
           (.setForce force?)
           (.call))
       (-> repo
           (.branchCreate)
           (.setName branch-name)
           (.setForce force?)
           (.setStartPoint start-point)
           (.call)))))

(defn git-branch-delete
  ([^Git repo branch-names] 
    (git-branch-delete repo branch-names false))
  ([^Git repo branch-names force?]
     (-> repo
         (.branchDelete)
         (.setBranchNames (into-array String branch-names))
         (.setForce force?)
         (.call))))

(defn git-checkout
  ([^Git repo branch-name] 
    (git-checkout repo branch-name false false nil))
  ([^Git repo branch-name create-branch?] 
    (git-checkout repo branch-name create-branch? false nil))
  ([^Git repo branch-name create-branch? force?] 
    (git-checkout repo branch-name create-branch? force? nil))
  ([^Git repo branch-name create-branch? force? ^String start-point]
     (if (nil? start-point)
       (-> repo
           (.checkout)
           (.setName branch-name)
           (.setCreateBranch create-branch?)
           (.setForce force?)
           (.call))
       (-> repo
           (.checkout)
           (.setName branch-name)
           (.setCreateBranch create-branch?)
           (.setForce force?)
           (.setStartPoint start-point)
           (.call)))))

(defn git-cherry-pick [])

(defn git-clone
  ([uri] 
    (git-clone uri (util/name-from-uri uri) "master" "master" false))
  ([uri local-dir] 
    (git-clone uri local-dir "master" "master" false))
  ([uri local-dir remote-branch] 
    (git-clone uri local-dir remote-branch "master" false))
  ([uri local-dir remote-branch local-branch] 
    (git-clone uri local-dir remote-branch local-branch false))
  ([uri local-dir remote-branch local-branch bare?]
     (-> (Git/cloneRepository)
         (.setURI uri)
         (.setDirectory (io/as-file local-dir))
         (.setRemote remote-branch)
         (.setBranch local-branch)
         (.setBare bare?)
         (.call))))

(declare git-fetch)
(declare git-merge)
(defn git-clone-full
  "Clone, fetch the master branch and merge its latest commit"
  ([uri] 
    (git-clone-full uri (util/name-from-uri uri) "master" "master" false))
  ([uri local-dir] 
    (git-clone-full uri local-dir "master" "master" false))
  ([uri local-dir remote-branch] 
    (git-clone-full uri local-dir remote-branch "master" false))
  ([uri local-dir remote-branch local-branch] 
    (git-clone-full uri local-dir remote-branch local-branch false))
  ([uri local-dir remote-branch local-branch bare?]
     (let [new-repo (-> (Git/cloneRepository)
                        (.setURI uri)
                        (.setDirectory (io/as-file local-dir))
                        (.setRemote remote-branch)
                        (.setBranch local-branch)
                        (.setBare bare?)
                        (.call))
           fetch-result ^FetchResult (git-fetch new-repo)
           merge-result (git-merge new-repo
                                   (first (.getAdvertisedRefs fetch-result)))]
       {:repo new-repo,
        :fetch-result fetch-result,
        :merge-result  merge-result})))

(defn git-commit
  ([^Git repo message]
     (-> repo
         (.commit)
         (.setMessage message)
         (.call)))
  ([^Git repo message {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setCommitter name email)
         (.call)))
  ([^Git repo message {:keys [author-name author-email]} {:keys [committer-name committer-email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor author-name author-email)
         (.setCommitter committer-name committer-email)
         (.call))))

(defn git-commit-amend
  ([^Git repo message]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAmend true)
         (.call)))
  ([^Git repo message {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setAmend true)
         (.call)))
  ([^Git repo message {:keys [name email]} {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setCommitter name email)
         (.setAmend true)
         (.call))))


(defn git-add-and-commit
  "This is the `git commit -a...` command"
  ([^Git repo message]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAll true)
         (.call)))
  ([^Git repo message {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setAll true)
         (.call)))
  ([^Git repo message {:keys [name email]} {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setCommitter name email)
         (.setAll true)
         (.call))))

(defn git-fetch
  (^org.eclipse.jgit.transport.FetchResult [^Git repo]
     (-> repo
         (.fetch)
         (.setRemote "master")
         (.call)))
  (^org.eclipse.jgit.transport.FetchResult [^Git repo remote]
     (-> repo
         (.fetch)
         (.setRemote remote)
         (.call))))

(defn git-init
  "Initialize and load a new Git repository"
  ([] (git-init "."))
  ([target-dir]
     (let [comm (InitCommand.)]
       (-> comm
           (.setDirectory (io/as-file target-dir))
           (.call)))))

(defn git-log
  "Return a seq of all commit objects"
  ([^Git repo]
    (seq (-> repo
           (.log)
           (.call))))
  ([^Git repo hash]
    (seq (-> repo
           (.log)
           (.add (resolve-object repo hash))
           (.call))))
  ([^Git repo hash-a hash-b]
    (seq (-> repo
           ^LogCommand (log-builder hash-a hash-b)
           (.call)))))

(defn- log-builder
  "Builds a log command object for a range of commit-ish names"
  ^org.eclipse.jgit.api.LogCommand [^Git repo hash-a hash-b]
  (let [log (.log repo)] 
    (if (= hash-a "0000000000000000000000000000000000000000")
      (.add log (resolve-object repo hash-b))
      (.addRange log (resolve-object repo hash-a) (resolve-object repo hash-b)))))

(defn git-merge
  [^Git repo commit-ref]
  (-> repo
      (.merge)
      ^MergeCommand (.include commit-ref)
      (.call)))

(defn git-pull
  "NOT WORKING: Requires work with configuration"
  [^Git repo]
  (-> repo
      (.pull)
      (.call)))

(defn git-push [])
(defn git-rebase [])
(defn git-revert [])
(defn git-rm
  [^Git repo file-pattern]
  (-> repo
      (.rm)
      (.addFilepattern file-pattern)
      (.call)))

(defn git-status
  "Return the status of the Git repository. Opts will return individual aspects of the status, and can be specified as `:added`, `:changed`, `:missing`, `:modified`, `:removed`, or `:untracked`."
  [^Git repo & fields]
  (let [status (.. repo status call)
        status-fns {:added     #(.getAdded ^Status %)
                    :changed   #(.getChanged ^Status %)
                    :missing   #(.getMissing ^Status %)
                    :modified  #(.getModified ^Status %)
                    :removed   #(.getRemoved ^Status %)
                    :untracked #(.getUntracked ^Status %)}]
    (if-not (seq fields)
      (apply merge (for [[k f] status-fns]
                     {k (into #{} (f status))}))
      (apply merge (for [field fields]
                     {field (into #{} ((field status-fns) status))})))))

(defn git-tag [])

(defonce fake-repo 
  (let [path (str (System/getProperty "java.io.tmpdir") "fake-empty-repo.clj-jgit")]
    (if-not (.exists (java.io.File. path))
      (git-init path)
      (load-repo path))))

(defn git-ls-remote
  ([^Git repo]
    (-> repo .lsRemote .call))
  ([^Git repo remote]
    (-> repo .lsRemote 
      (.setRemote remote) 
      .call))
  ([^Git repo remote opts]
    (-> repo .lsRemote
      (.setRemote remote)
      (.setHeads (:heads opts false))
      (.setTags (:tags opts false))
      (.call))))